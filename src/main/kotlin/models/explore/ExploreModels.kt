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
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    @SerialName("flyer_position") val flyerPosition: String? = null,
    @SerialName("registration_open") val registrationOpen: Boolean,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("organizer_id") val organizerId: String?,
    @SerialName("organizer_name") val organizerName: String?,
    @SerialName("organizer_logo_url") val organizerLogoUrl: String? = null,
    @SerialName("organizer_is_verified") val organizerIsVerified: Boolean = false,
    @SerialName("organizer_primary_color") val organizerPrimaryColor: String? = null,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("is_following_organizer") val isFollowingOrganizer: Boolean? = null
)

@Serializable
data class ExploreEventsResponse(
    val events: List<ExploreEvent>,
    val featured: List<ExploreEvent> = emptyList(),
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    @SerialName("has_more") val hasMore: Boolean
)

@Serializable
data class ExploreOrganizer(
    val id: String,
    val name: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("primary_color") val primaryColor: String = "#007AFF",
    @SerialName("is_verified") val isVerified: Boolean = false,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("follower_count") val followerCount: Long = 0,
    @SerialName("event_count") val eventCount: Int = 0
)

@Serializable
data class ExploreOrganizersResponse(
    val organizers: List<ExploreOrganizer>,
    val featured: List<ExploreOrganizer> = emptyList(),
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    @SerialName("has_more") val hasMore: Boolean,
)
