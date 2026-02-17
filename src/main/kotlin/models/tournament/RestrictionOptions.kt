package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RestrictionOptionsResponse(
    @SerialName("available_days_of_week") val availableDaysOfWeek: List<Int>,
    @SerialName("time_range") val timeRange: TimeRangeOption? = null
)

@Serializable
data class TimeRangeOption(
    val from: String, // "18:00"
    val to: String,   // "22:00"
    @SerialName("step_minutes") val stepMinutes: Int // 60
)
