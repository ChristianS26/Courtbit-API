package models.follow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FollowerCountResponse(
    @SerialName("follower_count") val followerCount: Long
)

@Serializable
data class IsFollowingResponse(
    @SerialName("is_following") val isFollowing: Boolean
)
