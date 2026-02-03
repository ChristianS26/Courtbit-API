package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.teams.PendingTournamentPlayerLinkResponse

/**
 * Combined response for all pending player links (leagues and tournaments)
 */
@Serializable
data class PendingLinksResponse(
    @SerialName("league_links") val leagueLinks: List<PendingPlayerLinkResponse>,
    @SerialName("tournament_links") val tournamentLinks: List<PendingTournamentPlayerLinkResponse>
)

/**
 * Response for pending player links
 * Represents a manual player that could be linked to the current user
 */
@Serializable
data class PendingPlayerLinkResponse(
    val id: String,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("category_color") val categoryColor: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("season_name") val seasonName: String
)

/**
 * Request to link a manual player to the current user's account
 */
@Serializable
data class LinkPlayerRequest(
    @SerialName("player_id") val playerId: String
)

/**
 * Response after successfully linking a player
 */
@Serializable
data class LinkPlayerResponse(
    @SerialName("linked_player") val linkedPlayer: LeaguePlayerResponse,
    val message: String
)
