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
