package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TournamentResponse(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val type: String,
    @SerialName("max_points") val maxPoints: String? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    val categoryIds: List<String> = emptyList(),
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("registration_open") val registrationOpen: Boolean,
    @SerialName("club_logo_url") val clubLogoUrl: String? = null,
    @SerialName("organizer_id") val organizerId: String? = null
)
