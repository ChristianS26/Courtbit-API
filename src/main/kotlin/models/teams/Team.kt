package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class Team(
    val id: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("player_a_uid") val playerAUid: String? = null,
    @SerialName("player_b_uid") val playerBUid: String? = null,
    @SerialName("player_a_paid") val playerAPaid: Boolean,
    @SerialName("player_b_paid") val playerBPaid: Boolean,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("categories") val category: CategoryResponseDto,
    @SerialName("restriction") val restriction: String? = null,
    // Manual player fields
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_a_email") val playerAEmail: String? = null,
    @SerialName("player_a_phone") val playerAPhone: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null,
    @SerialName("player_b_email") val playerBEmail: String? = null,
    @SerialName("player_b_phone") val playerBPhone: String? = null,
)
