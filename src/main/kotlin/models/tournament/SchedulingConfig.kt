package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CourtConfig(
    val id: String,
    val name: String,
    val number: Int,
    @SerialName("available_from") val availableFrom: String, // "09:00"
    @SerialName("available_to") val availableTo: String, // "22:00"
    @SerialName("is_active") val isActive: Boolean = true // Whether court is shown in calendar
)

@Serializable
data class SchedulingPhase(
    val id: String,
    val name: String,
    val type: String, // "group_stage" | "knockout"
    val days: List<String>, // ISO date strings
    @SerialName("time_from") val timeFrom: String, // "17:00"
    @SerialName("time_to") val timeTo: String, // "23:00"
    @SerialName("premium_time_from") val premiumTimeFrom: String? = null // "20:00"
)

@Serializable
data class SchedulingConfigRequest(
    val courts: List<CourtConfig>,
    @SerialName("match_duration_minutes") val matchDurationMinutes: Int,
    @SerialName("tournament_days") val tournamentDays: List<String>, // ISO date strings "2026-02-01"
    val phases: List<SchedulingPhase>? = null
)

@Serializable
data class SchedulingConfigResponse(
    @SerialName("tournament_id") val tournamentId: String,
    val courts: List<CourtConfig>,
    @SerialName("match_duration_minutes") val matchDurationMinutes: Int,
    @SerialName("tournament_days") val tournamentDays: List<String>,
    val phases: List<SchedulingPhase>? = null
)
