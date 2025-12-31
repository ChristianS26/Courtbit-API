package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeamWithResultStatusDto(
    val id: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("player_a_uid") val playerAUid: String,
    @SerialName("player_b_uid") val playerBUid: String,
    @SerialName("category_id") val categoryId: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,

    @SerialName("has_result") val hasResult: Boolean = false,
    val position: String? = null,
    @SerialName("points_awarded") val pointsAwarded: Int? = null,
    @SerialName("result_updated_at") val resultUpdatedAt: String? = null
)