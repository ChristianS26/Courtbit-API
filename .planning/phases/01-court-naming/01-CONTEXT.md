# Phase 1: Court Naming Configuration - Context

**Gathered:** 2026-01-22
**Status:** Ready for planning

<domain>
## Phase Boundary

Allow organizers to configure and name courts for a season. Courts are displayed by name (not just index) throughout the app - schedules, notifications, exports, and conflict messages. Includes database migration from court_index to court_id references.

</domain>

<decisions>
## Implementation Decisions

### Name display format
- Full court name only (e.g., "Cancha Central") - no "Court 1: Cancha Central" hybrid
- Optional with fallback - if no name set, show "Court 1", "Court 2", etc.
- 20 character maximum for court names
- Names must be unique within a season
- Notifications include court name: "You play at 19:30 on Cancha Central"
- Conflict reasons show court names: "Cancha Central preferred" instead of "Court 1 preferred"
- Exports (PDF/Excel) use court names, not indices
- API always provides a name (never null) - backend ensures this

### Court ordering & lifecycle
- Ordered by court number (1, 2, 3...) - matches current behavior
- Soft delete allowed - only affects future matchdays (played matchdays keep assignments)
- Can add new courts anytime during season
- New courts get next sequential number
- Soft-deleted courts can be reactivated
- Renaming a court updates everywhere (including past matchdays)
- `recommended_courts` in league_categories should reference court IDs, not indices
- When a category's recommended court is deleted, automatically remove from preferences

### Default behavior (migration)
- Auto-generate court names ("1", "2", "3"...) for existing seasons based on default_number_of_courts
- Create court records eagerly when season is created
- Require organizer to specify number of courts during season creation
- Migrate `day_groups.court_index` to `court_id` (FK to new courts table)
- Migration runs automatically on deploy
- Validate migration with report of any orphaned mappings (don't fail migration)

### Admin configuration UI
- Courts configured in season settings tab (alongside dates, fees, etc.)
- Courts configurable during season creation flow ("How many courts?")
- Number input for quick setup - enter count, auto-creates with default names
- No usage counts displayed - just show court names
- Always warn when changing courts on seasons with existing schedules
- Copy courts from previous season feature

### Claude's Discretion
- Court number behavior on deletion (recommend: fixed numbers for data integrity)
- How matchday_schedule_overrides works with named courts
- Edit UI pattern (inline list vs modal)
- Delete UI pattern (swipe vs edit mode)

</decisions>

<specifics>
## Specific Ideas

- Default names are just numbers ("1", "2", "3") - minimal, organizer can rename later
- This supports internationalization - no hardcoded "Court" or "Cancha" strings in defaults

</specifics>

<deferred>
## Deferred Ideas

- Court colors for visual distinction in schedule - future consideration

</deferred>

---

*Phase: 01-court-naming*
*Context gathered: 2026-01-22*
