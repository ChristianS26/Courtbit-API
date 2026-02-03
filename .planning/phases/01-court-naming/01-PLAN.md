# Phase 1: Court Naming - Implementation Plan

**Created:** 2026-01-22
**Status:** Ready for execution

---

## Overview

Migrate from integer-based court indices to UUID-referenced named courts. Non-breaking rollout strategy.

**Total Tasks:** 18
**Estimated Commits:** 6

---

## Task Breakdown

### Wave 1: Database Schema (Backend)

#### Task 1.1: Create `season_courts` table
**Files:** Supabase migration
**SQL:**
```sql
CREATE TABLE season_courts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    court_number INT NOT NULL,
    name VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(season_id, court_number),
    UNIQUE(season_id, name)
);

-- Enable RLS
ALTER TABLE season_courts ENABLE ROW LEVEL SECURITY;

-- RLS policies (match seasons pattern)
CREATE POLICY "Season courts are viewable by authenticated users"
ON season_courts FOR SELECT
TO authenticated
USING (true);

CREATE POLICY "Season courts are manageable by organizers"
ON season_courts FOR ALL
TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM seasons s
        JOIN organization_members om ON om.organizer_id = s.organizer_id
        WHERE s.id = season_courts.season_id
        AND om.user_uid = auth.uid()
    )
);
```

#### Task 1.2: Add `court_id` column to `day_groups`
**Files:** Supabase migration
**SQL:**
```sql
ALTER TABLE day_groups
ADD COLUMN court_id UUID REFERENCES season_courts(id);

-- Index for queries
CREATE INDEX idx_day_groups_court_id ON day_groups(court_id);
```

#### Task 1.3: Data migration - Create courts for existing seasons
**Files:** Supabase migration
**SQL:**
```sql
-- Create court records for each season based on defaults
INSERT INTO season_courts (season_id, court_number, name, is_active)
SELECT
    s.id as season_id,
    court_num,
    court_num::text as name,
    true as is_active
FROM seasons s
CROSS JOIN LATERAL generate_series(
    1,
    COALESCE(
        (SELECT default_number_of_courts FROM season_schedule_defaults WHERE season_id = s.id),
        4
    )
) as court_num
ON CONFLICT (season_id, court_number) DO NOTHING;
```

#### Task 1.4: Data migration - Populate `court_id` from `court_index`
**Files:** Supabase migration
**SQL:**
```sql
-- Map existing court_index to new court_id
UPDATE day_groups dg
SET court_id = sc.id
FROM match_days md
JOIN league_categories lc ON lc.id = md.category_id
JOIN season_courts sc ON sc.season_id = lc.season_id AND sc.court_number = dg.court_index
WHERE dg.match_day_id = md.id
AND dg.court_index IS NOT NULL;
```

**Commit 1:** `feat(db): add season_courts table and migrate existing data`

---

### Wave 2: Court Models & Repository (Backend)

#### Task 2.1: Create `CourtModels.kt`
**File:** `src/main/kotlin/models/league/CourtModels.kt`
```kotlin
@Serializable
data class SeasonCourtResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("court_number") val courtNumber: Int,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateSeasonCourtRequest(
    @SerialName("season_id") val seasonId: String,
    val name: String
)

@Serializable
data class UpdateSeasonCourtRequest(
    val name: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class BulkCreateCourtsRequest(
    @SerialName("season_id") val seasonId: String,
    val count: Int
)

@Serializable
data class CopySeasonCourtsRequest(
    @SerialName("source_season_id") val sourceSeasonId: String,
    @SerialName("target_season_id") val targetSeasonId: String
)
```

#### Task 2.2: Create `SeasonCourtRepository.kt` interface
**File:** `src/main/kotlin/repositories/league/SeasonCourtRepository.kt`
```kotlin
interface SeasonCourtRepository {
    suspend fun getBySeasonId(seasonId: String, includeInactive: Boolean = false): List<SeasonCourtResponse>
    suspend fun getById(id: String): SeasonCourtResponse?
    suspend fun create(seasonId: String, name: String): SeasonCourtResponse?
    suspend fun bulkCreate(seasonId: String, count: Int): List<SeasonCourtResponse>
    suspend fun update(id: String, request: UpdateSeasonCourtRequest): Boolean
    suspend fun softDelete(id: String): Boolean
    suspend fun reactivate(id: String): Boolean
    suspend fun copyFromSeason(sourceSeasonId: String, targetSeasonId: String): List<SeasonCourtResponse>
    suspend fun getNextCourtNumber(seasonId: String): Int
}
```

#### Task 2.3: Create `SeasonCourtRepositoryImpl.kt`
**File:** `src/main/kotlin/repositories/league/SeasonCourtRepositoryImpl.kt`
- Implement all repository methods
- Use Supabase HTTP client pattern (same as other repos)
- Handle unique constraint errors for duplicate names

#### Task 2.4: Add Koin DI registration
**File:** `src/main/kotlin/di/LeagueModule.kt`
- Add `SeasonCourtRepository` binding

**Commit 2:** `feat(api): add SeasonCourt model and repository`

---

### Wave 3: Court API Endpoints (Backend)

#### Task 3.1: Create `CourtRoutes.kt`
**File:** `src/main/kotlin/routing/league/CourtRoutes.kt`
```kotlin
fun Route.courtRoutes() {
    route("/seasons/{seasonId}/courts") {
        // GET - List courts for season
        get { }

        // POST - Create single court
        post { }

        // POST /bulk - Create multiple courts
        post("/bulk") { }

        // POST /copy - Copy from another season
        post("/copy") { }
    }

    route("/courts/{id}") {
        // GET - Get single court
        get { }

        // PATCH - Update court (name, is_active)
        patch { }

        // DELETE - Soft delete
        delete { }

        // POST /reactivate - Reactivate soft-deleted court
        post("/reactivate") { }
    }
}
```

#### Task 3.2: Register routes in `Application.kt`
**File:** `src/main/kotlin/Application.kt`
- Add `courtRoutes()` to routing configuration

#### Task 3.3: Add court validation on season creation
**File:** `src/main/kotlin/routing/league/SeasonRoutes.kt`
- When creating a season, require court count
- Auto-create court records with default names

**Commit 3:** `feat(api): add court CRUD endpoints`

---

### Wave 4: Enrich Existing APIs (Backend - Non-Breaking)

#### Task 4.1: Add court info to `DayGroupResponse`
**File:** `src/main/kotlin/models/league/MatchModels.kt`
```kotlin
data class DayGroupResponse(
    // ... existing fields ...
    val courtIndex: Int?,           // Keep for backwards compat
    val courtId: String? = null,    // NEW
    val courtName: String? = null,  // NEW
    val courtNumber: Int? = null    // NEW (same as courtIndex but clearer)
)
```

#### Task 4.2: Enrich day group queries with court name
**File:** `src/main/kotlin/repositories/league/DayGroupRepositoryImpl.kt`
- Join with `season_courts` table to get name
- Or fetch court separately and enrich

#### Task 4.3: Add court info to schedule responses
**File:** `src/main/kotlin/models/league/ScheduleModels.kt`
- Update `DayGroupScheduleInfo` to include `courtName`
- Update `GroupAssignment` to include `courtName`

#### Task 4.4: Update `MasterScheduleService.kt`
**File:** `src/main/kotlin/services/league/MasterScheduleService.kt`
- Include court names in schedule composition

**Commit 4:** `feat(api): enrich schedule responses with court names`

---

### Wave 5: Update Auto-Scheduling (Backend)

#### Task 5.1: Fetch courts instead of generating range
**File:** `src/main/kotlin/services/league/AutoSchedulingService.kt`

**Before:**
```kotlin
for (courtIndex in 1..numberOfCourts) {
    for (timeSlot in timeSlots) {
        availableSlots.add(Slot(matchDate, timeSlot, courtIndex))
    }
}
```

**After:**
```kotlin
val courts = courtRepository.getBySeasonId(seasonId, includeInactive = false)
for (court in courts) {
    for (timeSlot in timeSlots) {
        availableSlots.add(Slot(matchDate, timeSlot, court.id, court.courtNumber))
    }
}
```

#### Task 5.2: Update `Slot` data class
**File:** `src/main/kotlin/services/league/AutoSchedulingService.kt`
```kotlin
private data class Slot(
    val date: String,
    val timeSlot: String,
    val courtId: String,      // NEW - UUID reference
    val courtNumber: Int      // Keep for sorting/display
)
```

#### Task 5.3: Update assignment to use `court_id`
**File:** `src/main/kotlin/services/league/AutoSchedulingService.kt`
- Update `UpdateDayGroupAssignmentRequest` usage
- Write to `court_id` column instead of `court_index`

#### Task 5.4: Update `recommended_courts` handling
**File:** `src/main/kotlin/services/league/AutoSchedulingService.kt`
- Temporarily: map court IDs to court numbers for preference matching
- Or: update `league_categories.recommended_courts` to store UUIDs (Phase 2)

#### Task 5.5: Inject `SeasonCourtRepository` into service
**File:** `src/main/kotlin/services/league/AutoSchedulingService.kt`
- Add repository as constructor parameter
- Update Koin module

**Commit 5:** `feat(scheduling): use court_id in auto-scheduling algorithm`

---

### Wave 6: Update Assignment Endpoints (Backend)

#### Task 6.1: Update `UpdateDayGroupAssignmentRequest`
**File:** `src/main/kotlin/models/league/ScheduleModels.kt`
```kotlin
data class UpdateDayGroupAssignmentRequest(
    val matchDate: String?,
    val timeSlot: String?,
    val courtIndex: Int? = null,  // Deprecated, keep for compat
    val courtId: String? = null   // NEW - preferred
)
```

#### Task 6.2: Update assignment route to accept `court_id`
**File:** `src/main/kotlin/routing/league/ScheduleRoutes.kt`
- Accept either `courtId` or `courtIndex`
- If `courtIndex` provided, map to `courtId`
- Write to `court_id` column

#### Task 6.3: Update `findBySlot` to use `court_id`
**File:** `src/main/kotlin/repositories/league/DayGroupRepositoryImpl.kt`
- Add overload or update method to query by `court_id`

**Commit 6:** `feat(api): support court_id in assignment endpoints`

---

## Future Tasks (Phase 2)

These are deferred but noted:

- **Task F1:** Migrate `league_categories.recommended_courts` from `List<Int>` to `List<UUID>`
- **Task F2:** Drop `court_index` column from `day_groups`
- **Task F3:** Drop `default_number_of_courts` from `season_schedule_defaults`
- **Task F4:** Add court colors support
- **Task F5:** iOS court configuration UI
- **Task F6:** iOS schedule display with court names

---

## Dependency Graph

```
Wave 1 (DB)
├── 1.1 Create table
├── 1.2 Add court_id column
├── 1.3 Create court records ──────┐
└── 1.4 Populate court_id ─────────┴── depends on 1.1, 1.2, 1.3

Wave 2 (Models) ── depends on Wave 1
├── 2.1 CourtModels.kt
├── 2.2 Repository interface
├── 2.3 Repository impl ── depends on 2.1, 2.2
└── 2.4 Koin DI ── depends on 2.3

Wave 3 (Endpoints) ── depends on Wave 2
├── 3.1 CourtRoutes.kt
├── 3.2 Register routes
└── 3.3 Season creation validation

Wave 4 (Enrich) ── depends on Wave 2
├── 4.1 DayGroupResponse fields
├── 4.2 Enrich queries
├── 4.3 Schedule response fields
└── 4.4 MasterScheduleService

Wave 5 (Scheduling) ── depends on Wave 2, Wave 4
├── 5.1 Fetch courts
├── 5.2 Update Slot class
├── 5.3 Assignment uses court_id
├── 5.4 Recommended courts
└── 5.5 DI injection

Wave 6 (Assignment) ── depends on Wave 5
├── 6.1 Request model
├── 6.2 Route update
└── 6.3 Repository update
```

---

## Testing Checklist

- [ ] Create court for new season
- [ ] Bulk create courts (enter count)
- [ ] Copy courts from previous season
- [ ] Rename court - verify updates everywhere
- [ ] Soft delete court - verify removed from future scheduling
- [ ] Reactivate court
- [ ] Auto-schedule uses court names in responses
- [ ] Manual assignment accepts `court_id`
- [ ] Existing seasons have courts migrated
- [ ] No orphaned `day_groups` after migration

---

## Rollback Plan

1. **Database:** Keep `court_index` column populated alongside `court_id`
2. **API:** Keep accepting `courtIndex` in requests
3. **If issues:** Revert to using `court_index` in queries, ignore `court_id`

---

*Plan created: 2026-01-22*
