package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PendingTournamentPlayerLinkResponse(
    @SerialName("team_id")
    val teamId: String,
    @SerialName("player_position")
    val playerPosition: String, // "a" or "b"
    val name: String,
    val email: String?,
    val phone: String?,
    @SerialName("tournament_id")
    val tournamentId: String,
    @SerialName("tournament_name")
    val tournamentName: String,
    @SerialName("category_name")
    val categoryName: String
)

@Serializable
data class LinkTournamentPlayerRequest(
    @SerialName("team_id")
    val teamId: String,
    @SerialName("player_position")
    val playerPosition: String // "a" or "b"
)

@Serializable
data class LinkTournamentPlayerResponse(
    val success: Boolean,
    val message: String
)
