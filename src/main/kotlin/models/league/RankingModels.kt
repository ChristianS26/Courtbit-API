package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerStandingResponse(
    @SerialName("player_id") val playerId: String,
    @SerialName("user_uid") val userUid: String? = null,
    @SerialName("player_name") val playerName: String,
    @SerialName("points_for") val pointsFor: Int,
    @SerialName("points_against") val pointsAgainst: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int,
    val adjustment: Int,
    @SerialName("adjusted_points_for") val adjustedPointsFor: Int,
    @SerialName("point_diff") val pointDiff: Int,
    @SerialName("adjusted_diff") val adjustedDiff: Int,
    val rank: Int
)

@Serializable
data class LeagueAdjustmentResponse(
    val id: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("season_id") val seasonId: String,
    val value: Int,
    val reason: String,
    @SerialName("created_by_uid") val createdByUid: String?,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateAdjustmentRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("season_id") val seasonId: String,
    val value: Int,
    val reason: String
)

@Serializable
data class UpdateAdjustmentRequest(
    val value: Int? = null,
    val reason: String? = null
)
