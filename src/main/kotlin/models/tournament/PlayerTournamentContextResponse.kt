package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerTournamentContextResponse(
    val categories: List<CategoryContext>
)

@Serializable
data class CategoryContext(
    @SerialName("category_id") val categoryId: Int,
    @SerialName("category_name") val categoryName: String,
    @SerialName("team_id") val teamId: String,
    @SerialName("partner_name") val partnerName: String? = null,
    @SerialName("partner_photo_url") val partnerPhotoUrl: String? = null,
    val bracket: BracketContext? = null,
    val group: GroupContext? = null,
    val stats: StatsContext,
    @SerialName("next_match") val nextMatch: MatchContext? = null,
    @SerialName("last_match") val lastMatch: LastMatchContext? = null
)

@Serializable
data class BracketContext(
    @SerialName("bracket_id") val bracketId: String,
    val format: String,
    val status: String
)

@Serializable
data class GroupContext(
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("group_name") val groupName: String,
    val position: Int,
    @SerialName("total_teams") val totalTeams: Int
)

@Serializable
data class StatsContext(
    @SerialName("matches_played") val matchesPlayed: Int,
    @SerialName("matches_won") val matchesWon: Int,
    @SerialName("matches_lost") val matchesLost: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int
)

@Serializable
data class MatchContext(
    @SerialName("match_id") val matchId: String,
    @SerialName("opponent_team_name") val opponentTeamName: String? = null,
    @SerialName("scheduled_time") val scheduledTime: String? = null,
    @SerialName("court_number") val courtNumber: Int? = null,
    @SerialName("round_name") val roundName: String? = null
)

@Serializable
data class LastMatchContext(
    @SerialName("match_id") val matchId: String,
    @SerialName("opponent_team_name") val opponentTeamName: String? = null,
    val won: Boolean,
    @SerialName("score_display") val scoreDisplay: String
)
