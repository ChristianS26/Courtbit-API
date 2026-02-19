package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssignedRankingEvent(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("team_member_id") val teamMemberId: String? = null,
    val position: String,
    @SerialName("points_earned") val pointsEarned: Int,
    @SerialName("user") val user: PublicUser? = null,
)

@Serializable
data class AssignedRankingEventResponse(
    val userId: String? = null,
    val teamMemberId: String? = null,
    val playerName: String,
    val position: String,
    val pointsEarned: Int,
)
