# Phase 1: Court Naming - Research

**Researched:** 2026-01-22
**Status:** Complete

## Current Implementation Summary

Courts are purely **integer-based** (1, 2, 3, 4...) with no names or dedicated table.

### Database Schema (Current)

| Table | Field | Type | Purpose |
|-------|-------|------|---------|
| `day_groups` | `court_index` | `Int?` | 1-based court assignment |
| `season_schedule_defaults` | `default_number_of_courts` | `Int` | Default court count (typically 4) |
| `matchday_schedule_overrides` | `number_of_courts_override` | `Int?` | Per-matchday court count |
| `league_categories` | `recommended_courts` | `List<Int>?` | Preferred court indices |

### Key Files Requiring Changes

| File | Changes Needed |
|------|----------------|
| `services/league/AutoSchedulingService.kt` | Replace `1..numberOfCourts` loop with court UUID iteration; update `Slot` data class |
| `models/league/ScheduleModels.kt` | Replace `courtIndex: Int?` with `courtId: String?` in all DTOs |
| `models/league/LeagueCategoryModels.kt` | Update `recommendedCourts: List<Int>?` to `List<String>?` (UUIDs) |
| `models/league/MatchModels.kt` | Update `DayGroupResponse.courtIndex` to `courtId` |
| `repositories/league/DayGroupRepositoryImpl.kt` | Update `findBySlot()` query and PATCH operations |
| `routing/league/ScheduleRoutes.kt` | Update request/response handling |
| `routing/league/LeagueCategoryRoutes.kt` | Update recommended courts endpoint |
| `services/league/MasterScheduleService.kt` | Update schedule composition |

---

## Auto-Scheduling Algorithm Analysis

### Current Slot Generation (`AutoSchedulingService.kt:156-160`)
```kotlin
val availableSlots = mutableSetOf<Slot>()
for (courtIndex in 1..numberOfCourts) {  // Simple integer loop
    for (timeSlot in timeSlots) {
        availableSlots.add(Slot(matchDate, timeSlot, courtIndex))
    }
}
```

### Current Slot Data Class (`AutoSchedulingService.kt:770-774`)
```kotlin
private data class Slot(
    val date: String,
    val timeSlot: String,
    val courtIndex: Int  // Must become courtId: String (UUID)
)
```

### Court Preference Logic (`AutoSchedulingService.kt:228-236`)
```kotlin
val perfectSlot = if (recommendedCourts != null && recommendedCourts.isNotEmpty()) {
    perfectSlotsWithVariety.firstOrNull {
        it.slotScore.slot.courtIndex in recommendedCourts  // Integer comparison
    }?.slotScore
    ?: perfectSlotsWithVariety.firstOrNull()?.slotScore
} else {
    perfectSlotsWithVariety.firstOrNull()?.slotScore
}
```

---

## Migration Strategy

### Phase A: Database Schema

1. **Create `season_courts` table:**
```sql
CREATE TABLE season_courts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    court_number INT NOT NULL,  -- Keeps ordering (1, 2, 3...)
    name VARCHAR(20) NOT NULL,  -- Max 20 chars per context
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(season_id, court_number),
    UNIQUE(season_id, name)  -- Names unique within season
);
```

2. **Add `court_id` to `day_groups`:**
```sql
ALTER TABLE day_groups ADD COLUMN court_id UUID REFERENCES season_courts(id);
```

3. **Update `league_categories.recommended_courts`:**
```sql
-- Change from integer array to UUID array
ALTER TABLE league_categories
    ALTER COLUMN recommended_courts TYPE UUID[]
    USING NULL;  -- Will need data migration
```

4. **Remove old columns (after migration verified):**
```sql
ALTER TABLE day_groups DROP COLUMN court_index;
ALTER TABLE season_schedule_defaults DROP COLUMN default_number_of_courts;
ALTER TABLE matchday_schedule_overrides DROP COLUMN number_of_courts_override;
```

### Phase B: Data Migration

```sql
-- 1. Create court records for each season based on current defaults
INSERT INTO season_courts (season_id, court_number, name, is_active)
SELECT
    s.id,
    generate_series(1, COALESCE(d.default_number_of_courts, 4)),
    generate_series(1, COALESCE(d.default_number_of_courts, 4))::text,
    true
FROM seasons s
LEFT JOIN season_schedule_defaults d ON d.season_id = s.id;

-- 2. Populate day_groups.court_id from court_index
UPDATE day_groups dg
SET court_id = sc.id
FROM season_courts sc
JOIN match_days md ON md.id = dg.match_day_id
JOIN league_categories lc ON lc.id = md.category_id
WHERE sc.season_id = lc.season_id
  AND sc.court_number = dg.court_index;

-- 3. Update league_categories.recommended_courts to UUIDs
-- (Complex - needs per-category mapping)
```

### Phase C: Application Code

**Order of changes:**

1. **New files to create:**
   - `models/league/CourtModels.kt` - Court DTOs
   - `repositories/league/SeasonCourtRepository.kt` - Court data access
   - `routing/league/CourtRoutes.kt` - Court CRUD endpoints

2. **Update DTOs (ScheduleModels.kt, MatchModels.kt):**
   - Add `courtId: String?` alongside `courtIndex: Int?` temporarily
   - Add `courtName: String?` for display

3. **Update AutoSchedulingService.kt:**
   - Fetch active courts for season instead of generating range
   - Update `Slot` data class
   - Update preference matching logic

4. **Update repositories:**
   - `DayGroupRepositoryImpl.kt` - Use `court_id` in queries
   - Add court name enrichment to responses

5. **New API endpoints:**
   - `GET /seasons/{id}/courts` - List courts
   - `POST /seasons/{id}/courts` - Create court
   - `PATCH /courts/{id}` - Update court name
   - `DELETE /courts/{id}` - Soft delete court

### Phase D: iOS App Updates

The iOS app will need:
- Updated models to handle `courtId` and `courtName`
- UI for court configuration in season settings
- Display court names in schedule views

---

## API Response Changes

### Current `DayGroupResponse`:
```json
{
    "id": "uuid",
    "courtIndex": 1,
    "matchDate": "2026-01-22",
    "timeSlot": "18:30:00"
}
```

### New `DayGroupResponse`:
```json
{
    "id": "uuid",
    "courtId": "uuid",
    "courtName": "Cancha Central",
    "courtNumber": 1,
    "matchDate": "2026-01-22",
    "timeSlot": "18:30:00"
}
```

### New `SeasonCourtResponse`:
```json
{
    "id": "uuid",
    "seasonId": "uuid",
    "courtNumber": 1,
    "name": "Cancha Central",
    "isActive": true
}
```

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Breaking iOS app during migration | Add `courtName` to API first (backwards compatible), then migrate |
| Data loss on court_index migration | Keep `court_index` column until migration verified |
| Orphaned day_groups | Validation report before dropping old columns |
| Auto-scheduling performance | Minimal - just fetching court list instead of generating range |

---

## Implementation Order Recommendation

1. **Backend first (non-breaking):**
   - Create `season_courts` table
   - Add `court_id` column to `day_groups` (nullable)
   - Create court CRUD endpoints
   - Run data migration to populate courts

2. **Backend enrichment:**
   - Add `courtName` to API responses (keeps `courtIndex` too)
   - This allows iOS to start using names without breaking

3. **iOS updates:**
   - Court configuration UI in season settings
   - Display court names in schedule

4. **Backend cleanup:**
   - Switch auto-scheduling to use `court_id`
   - Remove `court_index` from responses
   - Drop old columns

---

*Research completed: 2026-01-22*
