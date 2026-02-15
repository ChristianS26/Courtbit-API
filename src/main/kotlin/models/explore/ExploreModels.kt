package models.explore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExploreEvent(
    val id: String,
    val name: String,
    val type: String, // "tournament" or "league"
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    val location: String? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    @SerialName("registration_open") val registrationOpen: Boolean,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("organizer_id") val organizerId: String?,
    @SerialName("organizer_name") val organizerName: String?,
    @SerialName("organizer_logo_url") val organizerLogoUrl: String? = null,
    @SerialName("organizer_is_verified") val organizerIsVerified: Boolean = false
)

@Serializable
data class ExploreEventsResponse(
    val events: List<ExploreEvent>,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    @SerialName("has_more") val hasMore: Boolean
)
