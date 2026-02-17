package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RestrictionConfigResponse(
    @SerialName("tournament_id") val tournamentId: String,
    val enabled: Boolean = false,
    @SerialName("available_days") val availableDays: List<Int> = emptyList(),
    @SerialName("time_range_from") val timeRangeFrom: String? = null,
    @SerialName("time_range_to") val timeRangeTo: String? = null,
    @SerialName("time_slot_minutes") val timeSlotMinutes: Int = 60
)

@Serializable
data class RestrictionConfigRequest(
    val enabled: Boolean,
    @SerialName("available_days") val availableDays: List<Int>,
    @SerialName("time_range_from") val timeRangeFrom: String? = null,
    @SerialName("time_range_to") val timeRangeTo: String? = null,
    @SerialName("time_slot_minutes") val timeSlotMinutes: Int = 60
)
