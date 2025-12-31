package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class Team(
    val id: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("player_a_uid") val playerAUid: String,
    @SerialName("player_b_uid") val playerBUid: String,
    @SerialName("player_a_paid") val playerAPaid: Boolean,
    @SerialName("player_b_paid") val playerBPaid: Boolean,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("categories") val category: CategoryResponseDto,
    @SerialName("restriction") val restriction: String? = null,
)
