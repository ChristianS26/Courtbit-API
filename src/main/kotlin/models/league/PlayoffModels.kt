package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayoffStatusResponse(
    @SerialName("regular_season_complete") val regularSeasonComplete: Boolean,
    @SerialName("semifinals_complete") val semifinalsComplete: Boolean,
    @SerialName("can_assign_semifinals") val canAssignSemifinals: Boolean,
    @SerialName("can_assign_final") val canAssignFinal: Boolean
)

@Serializable
data class AssignPlayoffResponse(
    val success: Boolean,
    val message: String? = null,
    @SerialName("players_assigned") val playersAssigned: Int? = null,
    val groups: Int? = null,
    @SerialName("direct_qualifiers") val directQualifiers: Int? = null,
    @SerialName("semifinals_winners") val semifinalsWinners: Int? = null,
    @SerialName("total_in_final") val totalInFinal: Int? = null,
    @SerialName("final_groups") val finalGroups: Int? = null
)

// Playoff Bracket Models
@Serializable
data class PlayoffBracketResponse(
    @SerialName("categoryId") val categoryId: String,
    val semifinals: List<PlayoffGroupData> = emptyList(),
    val final: List<PlayoffGroupData> = emptyList()
)

@Serializable
data class PlayoffGroupData(
    @SerialName("group_id") val groupId: String,
    @SerialName("grp_num") val groupNumber: Int,
    val matchday: Int,
    val standings: List<PlayoffPlayerStanding> = emptyList(),
    val ties: List<PlayoffTieInfo> = emptyList()
)

@Serializable
data class PlayoffPlayerStanding(
    @SerialName("player_id") val playerId: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("standing_position") val standingPosition: Int,
    @SerialName("points_for") val pointsFor: Int,
    @SerialName("points_against") val pointsAgainst: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int,
    @SerialName("is_manual_override") val isManualOverride: Boolean = false,
    @SerialName("advances_to_next") val advancesToNext: Boolean = false
)

@Serializable
data class PlayoffTieInfo(
    @SerialName("tie_position") val tiePosition: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("tied_points_for") val tiedPointsFor: Int,
    @SerialName("tied_points_against") val tiedPointsAgainst: Int,
    @SerialName("is_tie") val isTie: Boolean
)

// Calculated standings response (from calculate_playoff_group_standings)
@Serializable
data class PlayoffGroupStandingsResponse(
    @SerialName("day_group_id") val dayGroupId: String,
    val standings: List<CalculatedStanding>
)

@Serializable
data class CalculatedStanding(
    @SerialName("player_id") val playerId: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("points_for") val pointsFor: Int,
    @SerialName("points_against") val pointsAgainst: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int,
    @SerialName("point_diff") val pointDiff: Int,
    @SerialName("calculated_position") val calculatedPosition: Int
)

// Tie detection response
@Serializable
data class PlayoffTiesResponse(
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("has_ties") val hasTies: Boolean,
    val ties: List<PlayoffTieInfo>
)

// Resolve tie request/response
@Serializable
data class ResolveTieRequest(
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("player_positions") val playerPositions: List<PlayerPositionAssignment>
)

@Serializable
data class PlayerPositionAssignment(
    @SerialName("player_id") val playerId: String,
    val position: Int
)

@Serializable
data class ResolveTieResponse(
    val success: Boolean,
    val message: String? = null,
    @SerialName("groupId") val groupId: String? = null,
    @SerialName("playersUpdated") val playersUpdated: Int? = null,
    val error: String? = null
)

@Serializable
data class SaveStandingsResponse(
    val success: Boolean,
    val message: String? = null,
    @SerialName("groupsProcessed") val groupsProcessed: Int? = null,
    @SerialName("totalPlayers") val totalPlayers: Int? = null,
    val error: String? = null
)
