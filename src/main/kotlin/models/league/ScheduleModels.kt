package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Season Schedule Defaults
@Serializable
data class SeasonScheduleDefaultsResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("default_number_of_courts") val defaultNumberOfCourts: Int,
    @SerialName("default_time_slots") val defaultTimeSlots: List<String>, // ["18:30:00", "19:45:00", "21:00:00"]
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateSeasonScheduleDefaultsRequest(
    @SerialName("season_id") val seasonId: String,
    @SerialName("default_number_of_courts") val defaultNumberOfCourts: Int = 4,
    @SerialName("default_time_slots") val defaultTimeSlots: List<String> = listOf("18:30:00", "19:45:00", "21:00:00")
)

@Serializable
data class UpdateSeasonScheduleDefaultsRequest(
    @SerialName("default_number_of_courts") val defaultNumberOfCourts: Int? = null,
    @SerialName("default_time_slots") val defaultTimeSlots: List<String>? = null
)

// Matchday Schedule Overrides
@Serializable
data class MatchdayScheduleOverrideResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("match_date") val matchDate: String,
    @SerialName("number_of_courts_override") val numberOfCourtsOverride: Int?,
    @SerialName("time_slots_override") val timeSlotsOverride: List<String>?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateMatchdayScheduleOverrideRequest(
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("match_date") val matchDate: String,
    @SerialName("number_of_courts_override") val numberOfCourtsOverride: Int? = null,
    @SerialName("time_slots_override") val timeSlotsOverride: List<String>? = null
)

@Serializable
data class UpdateMatchdayScheduleOverrideRequest(
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("number_of_courts_override") val numberOfCourtsOverride: Int? = null,
    @SerialName("time_slots_override") val timeSlotsOverride: List<String>? = null
)

// Update DayGroup Assignment
@Serializable
data class UpdateDayGroupAssignmentRequest(
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?
)

@Serializable
data class UpdateAssignmentResponse(
    val success: Boolean,
    val action: String, // "assigned", "swapped", "displaced"
    @SerialName("displaced_group_id") val displacedGroupId: String? = null,
    @SerialName("displaced_group_number") val displacedGroupNumber: Int? = null
)

// Master Schedule Response (combines defaults + overrides + day groups)
@Serializable
data class MasterScheduleResponse(
    @SerialName("season_id") val seasonId: String,
    @SerialName("defaults") val defaults: SeasonScheduleDefaultsResponse?,
    @SerialName("matchday_overrides") val matchdayOverrides: List<MatchdayScheduleOverrideResponse>,
    @SerialName("match_days") val matchDays: List<MatchDayWithGroupsResponse>
)

@Serializable
data class MatchDayWithGroupsResponse(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("day_groups") val dayGroups: List<DayGroupScheduleInfo>,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class DayGroupScheduleInfo(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String
)

// Auto-scheduling request/response
@Serializable
data class AutoScheduleRequest(
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("match_date") val matchDate: String? = null, // Legacy: single date for all categories (optional for backward compat)
    @SerialName("respect_availability") val respectAvailability: Boolean = true,
    @SerialName("prefer_time_slot_variety") val preferTimeSlotVariety: Boolean = true,
    @SerialName("strict_mode") val strictMode: Boolean = false, // When false (flexible), schedule all groups even with conflicts
    @SerialName("category_ids") val categoryIds: List<String>? = null, // Optional: only schedule specific categories (legacy)
    @SerialName("category_dates") val categoryDates: Map<String, String>? = null // NEW: per-category dates (categoryId -> date)
)

// Preview request - same as AutoScheduleRequest but for dry-run
@Serializable
data class AutoSchedulePreviewRequest(
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("category_ids") val categoryIds: List<String>? = null // Optional: only preview specific categories
)

// Preview response with availability breakdown
@Serializable
data class AutoSchedulePreviewResponse(
    @SerialName("total_groups") val totalGroups: Int,
    @SerialName("groups_full_availability") val groupsFullAvailability: Int,
    @SerialName("groups_partial_availability") val groupsPartialAvailability: Int,
    @SerialName("groups_no_availability") val groupsNoAvailability: Int,
    @SerialName("time_slot_availability") val timeSlotAvailability: List<TimeSlotAvailabilityInfo>,
    @SerialName("category_previews") val categoryPreviews: List<CategoryPreviewInfo>
)

@Serializable
data class TimeSlotAvailabilityInfo(
    @SerialName("time_slot") val timeSlot: String,
    @SerialName("available_players") val availablePlayers: Int,
    @SerialName("total_players") val totalPlayers: Int,
    @SerialName("availability_percentage") val availabilityPercentage: Double
)

@Serializable
data class CategoryPreviewInfo(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("total_groups") val totalGroups: Int,
    @SerialName("groups_full_availability") val groupsFullAvailability: Int,
    @SerialName("groups_partial_availability") val groupsPartialAvailability: Int,
    @SerialName("groups_no_availability") val groupsNoAvailability: Int,
    @SerialName("availability_percentage") val availabilityPercentage: Double
)

@Serializable
data class AutoScheduleResponse(
    @SerialName("total_groups") val totalGroups: Int,
    @SerialName("assigned_groups") val assignedGroups: Int,
    @SerialName("skipped_groups") val skippedGroups: Int,
    val assignments: List<GroupAssignment>,
    val warnings: List<String>
)

@Serializable
data class GroupAssignment(
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("category_name") val categoryName: String,
    @SerialName("match_date") val matchDate: String,
    @SerialName("time_slot") val timeSlot: String,
    @SerialName("court_index") val courtIndex: Int,
    @SerialName("availability_score") val availabilityScore: Double,
    @SerialName("unavailable_players") val unavailablePlayers: List<String>
)

// Bulk Schedule Response - optimized single-request endpoint
@Serializable
data class BulkScheduleResponse(
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    val categories: List<LeagueCategoryResponse>,
    val defaults: SeasonScheduleDefaultsResponse?,
    @SerialName("matchday_overrides") val matchdayOverrides: List<MatchdayScheduleOverrideResponse>,
    @SerialName("category_schedules") val categorySchedules: List<CategoryScheduleData>
)

@Serializable
data class CategoryScheduleData(
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_day") val matchDay: MatchDayWithGroupsEnriched?
)

@Serializable
data class MatchDayWithGroupsEnriched(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("day_groups") val dayGroups: List<DayGroupEnriched>,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class DayGroupEnriched(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String,
    val players: List<LeaguePlayerResponse>,
    @SerialName("completed_rotations") val completedRotations: Int,
    @SerialName("total_rotations") val totalRotations: Int
)

// Phase 3.2: Schedule Health Dashboard
@Serializable
data class ScheduleHealthResponse(
    @SerialName("season_id") val seasonId: String,
    @SerialName("overall_percentage") val overallPercentage: Double,
    @SerialName("matchday_health") val matchdayHealth: List<MatchdayHealthInfo>,
    @SerialName("total_groups") val totalGroups: Int,
    @SerialName("scheduled_groups") val scheduledGroups: Int,
    @SerialName("unscheduled_groups") val unscheduledGroups: Int
)

@Serializable
data class MatchdayHealthInfo(
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("total_groups") val totalGroups: Int,
    @SerialName("scheduled_groups") val scheduledGroups: Int,
    @SerialName("unscheduled_groups") val unscheduledGroups: Int,
    @SerialName("completion_percentage") val completionPercentage: Double
)
