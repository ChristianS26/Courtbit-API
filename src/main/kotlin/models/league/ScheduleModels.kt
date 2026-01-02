package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeasonScheduleDefaultsResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("default_number_of_courts") val defaultNumberOfCourts: Int,
    @SerialName("default_time_slots") val defaultTimeSlots: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class UpdateScheduleDefaultsRequest(
    @SerialName("default_number_of_courts") val defaultNumberOfCourts: Int? = null,
    @SerialName("default_time_slots") val defaultTimeSlots: List<String>? = null
)

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
data class GenerateCalendarResponse(
    val success: Boolean,
    val matchDaysCreated: Int,
    val totalMatches: Int
)
