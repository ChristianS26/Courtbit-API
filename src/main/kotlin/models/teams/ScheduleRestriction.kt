package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScheduleRestriction(
    val type: String, // "play_only_day" | "exclude_day" | "play_from_time"
    @SerialName("day_of_week") val dayOfWeek: Int? = null, // 1-7 (ISO: 1=Mon..7=Sun)
    @SerialName("start_time") val startTime: String? = null // "HH:mm"
)
