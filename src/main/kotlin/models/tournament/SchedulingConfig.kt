package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CourtConfig(
    val id: String,
    val name: String,
    val number: Int,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class DaySchedule(
    val date: String, // ISO date "2026-03-01"
    @SerialName("time_from") val timeFrom: String, // "17:00"
    @SerialName("time_to") val timeTo: String, // "23:00"
    @SerialName("court_count") val courtCount: Int = 3, // Number of courts for this day
    @SerialName("premium_time_from") val premiumTimeFrom: String? = null // "20:00"
)

@Serializable
data class SchedulingConfigRequest(
    val courts: List<CourtConfig>? = null,
    @SerialName("match_duration_minutes") val matchDurationMinutes: Int,
    @SerialName("tournament_days") val tournamentDays: List<String>,
    @SerialName("day_schedules") val daySchedules: List<DaySchedule>? = null
)

@Serializable
data class SchedulingConfigResponse(
    @SerialName("tournament_id") val tournamentId: String,
    val courts: List<CourtConfig>? = null,
    @SerialName("match_duration_minutes") val matchDurationMinutes: Int,
    @SerialName("tournament_days") val tournamentDays: List<String>,
    @SerialName("day_schedules") val daySchedules: List<DaySchedule>? = null
)
