package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddRankingEventRequest(
    @SerialName("p_user_id")
    val userId: String,

    @SerialName("p_season")
    val season: String,

    @SerialName("p_category_id")
    val categoryId: Int,

    @SerialName("p_points")
    val points: Int,

    @SerialName("p_tournament_id")
    val tournamentId: String? = null,

    @SerialName("p_position")
    val position: String? = null
)

