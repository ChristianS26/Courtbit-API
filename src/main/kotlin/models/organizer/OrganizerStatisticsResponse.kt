package models.organizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrganizerStatisticsResponse(
    @SerialName("total_tournaments") val totalTournaments: Long,
    @SerialName("active_tournaments") val activeTournaments: Long,
    @SerialName("total_registrations") val totalRegistrations: Long,
    @SerialName("total_revenue") val totalRevenue: Long
)
