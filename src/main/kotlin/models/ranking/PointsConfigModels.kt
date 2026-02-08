package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PointDistributionItem(
    val position: String,
    val points: Int
)

@Serializable
data class PointsConfigResponse(
    val id: String,
    @SerialName("organizer_id") val organizerId: String,
    val name: String,
    val distribution: List<PointDistributionItem>,
)

@Serializable
data class CreatePointsConfigRequest(
    val name: String,
    val distribution: List<PointDistributionItem>,
)

@Serializable
data class UpdatePointsConfigRequest(
    val name: String? = null,
    val distribution: List<PointDistributionItem>? = null,
)

@Serializable
data class BatchRankingRequest(
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("category_id") val categoryId: Int,
    val season: String,
    val entries: List<BatchRankingEntry>,
)

@Serializable
data class BatchRankingEntry(
    @SerialName("user_id") val userId: String? = null,
    val points: Int,
    val position: String,
    @SerialName("team_result_id") val teamResultId: String? = null,
    @SerialName("team_member_id") val teamMemberId: String? = null,
)
