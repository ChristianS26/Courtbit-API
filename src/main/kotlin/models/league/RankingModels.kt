package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerStandingResponse(
    @SerialName("player_id") val playerId: String,
    @SerialName("user_uid") val userUid: String? = null,
    @SerialName("player_name") val playerName: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("points_for") val pointsFor: Int,
    @SerialName("points_against") val pointsAgainst: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int,
    val adjustment: Int,
    @SerialName("adjusted_points_for") val adjustedPointsFor: Int,
    @SerialName("point_diff") val pointDiff: Int,
    @SerialName("adjusted_diff") val adjustedDiff: Int,
    val rank: Int,
    // Primary criterion info - populated by API based on season configuration
    @SerialName("primary_criterion") val primaryCriterion: String? = null,
    @SerialName("primary_value") val primaryValue: Int? = null
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
    @SerialName("created_at") val createdAt: String,
    // Per-stat adjustments
    @SerialName("points_for_adj") val pointsForAdj: Int? = null,
    @SerialName("points_against_adj") val pointsAgainstAdj: Int? = null,
    @SerialName("games_won_adj") val gamesWonAdj: Int? = null,
    @SerialName("games_lost_adj") val gamesLostAdj: Int? = null
)

@Serializable
data class CreateAdjustmentRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("season_id") val seasonId: String,
    val value: Int,
    val reason: String,
    // Per-stat adjustments (optional)
    @SerialName("points_for_adj") val pointsForAdj: Int? = null,
    @SerialName("points_against_adj") val pointsAgainstAdj: Int? = null,
    @SerialName("games_won_adj") val gamesWonAdj: Int? = null,
    @SerialName("games_lost_adj") val gamesLostAdj: Int? = null
)

@Serializable
data class UpdateAdjustmentRequest(
    val value: Int? = null,
    val reason: String? = null,
    @SerialName("points_for_adj") val pointsForAdj: Int? = null,
    @SerialName("points_against_adj") val pointsAgainstAdj: Int? = null,
    @SerialName("games_won_adj") val gamesWonAdj: Int? = null,
    @SerialName("games_lost_adj") val gamesLostAdj: Int? = null
)
