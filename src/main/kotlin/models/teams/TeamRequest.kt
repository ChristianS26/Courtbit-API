package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeamRequest(
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("player_a_uid") val playerAUid: String? = null,
    @SerialName("player_b_uid") val playerBUid: String? = null,
    @SerialName("category_id")  val categoryId: Int,
    @SerialName("player_a_paid") val playerAPaid: Boolean = false,
    @SerialName("player_b_paid") val playerBPaid: Boolean = false,
    val restriction: String? = null,
    @SerialName("schedule_restriction") val scheduleRestriction: kotlinx.serialization.json.JsonElement? = null,
    // Manual player fields
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_a_email") val playerAEmail: String? = null,
    @SerialName("player_a_phone") val playerAPhone: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null,
    @SerialName("player_b_email") val playerBEmail: String? = null,
    @SerialName("player_b_phone") val playerBPhone: String? = null,
)
