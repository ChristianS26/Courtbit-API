package models.organizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.explore.ExploreEvent

@Serializable
data class OrganizerPublicProfileResponse(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("primary_color") val primaryColor: String = "#007AFF",
    @SerialName("secondary_color") val secondaryColor: String = "#5856D6",
    @SerialName("contact_email") val contactEmail: String,
    @SerialName("contact_phone") val contactPhone: String = "",
    val instagram: String? = null,
    val facebook: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("follower_count") val followerCount: Long = 0,
    @SerialName("is_following") val isFollowing: Boolean = false,
    @SerialName("upcoming_events") val upcomingEvents: List<ExploreEvent> = emptyList(),
    @SerialName("past_events") val pastEvents: List<ExploreEvent> = emptyList()
)
