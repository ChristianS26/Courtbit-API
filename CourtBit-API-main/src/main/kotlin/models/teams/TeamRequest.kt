package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeamRequest(
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("player_a_uid") val playerAUid: String,
    @SerialName("player_b_uid") val playerBUid: String,
    @SerialName("category_id")  val categoryId: Int,
    @SerialName("player_a_paid") val playerAPaid: Boolean = false,
    @SerialName("player_b_paid") val playerBPaid: Boolean = false,
    val restriction: String? = null
)
