package models.bracket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ============ Request DTOs ============

@Serializable
data class GenerateBracketRequest(
    @SerialName("seeding_method") val seedingMethod: String,  // "random", "manual", "ranking"
    @SerialName("team_ids") val teamIds: List<String>         // Ordered by seed if manual
)

@Serializable
data class PublishBracketRequest(
    val publish: Boolean = true
)

@Serializable
data class CreateBracketRequest(
    val format: String,                                          // "americano", "mexicano", "knockout", "round_robin", "groups_knockout"
    @SerialName("seeding_method") val seedingMethod: String      // "random", "manual", "ranking"
    // config is ignored for now - we use defaults
)

// ============ Response DTOs ============

/**
 * Response DTO matching tournament_brackets table
 */
@Serializable
data class BracketResponse(
    val id: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("category_id") val categoryId: Int,
    val format: String,
    val status: String,
    val config: JsonElement? = null,
    @SerialName("seeding_method") val seedingMethod: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Response DTO matching tournament_matches table
 */
@Serializable
data class MatchResponse(
    val id: String,
    @SerialName("bracket_id") val bracketId: String,
    @SerialName("round_number") val roundNumber: Int,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("round_name") val roundName: String? = null,
    @SerialName("team1_id") val team1Id: String? = null,
    @SerialName("team2_id") val team2Id: String? = null,
    // Player IDs from teams table (populated when fetching with bracket)
    @SerialName("team1_player1_id") val team1Player1Id: String? = null,
    @SerialName("team1_player2_id") val team1Player2Id: String? = null,
    @SerialName("team2_player1_id") val team2Player1Id: String? = null,
    @SerialName("team2_player2_id") val team2Player2Id: String? = null,
    @SerialName("score_team1") val scoreTeam1: Int? = null,
    @SerialName("score_team2") val scoreTeam2: Int? = null,
    @SerialName("set_scores") val setScores: String? = null,  // JSON string
    @SerialName("winner_team") val winnerTeam: Int? = null,
    @SerialName("next_match_id") val nextMatchId: String? = null,
    @SerialName("next_match_position") val nextMatchPosition: Int? = null,
    @SerialName("loser_next_match_id") val loserNextMatchId: String? = null,
    @SerialName("group_number") val groupNumber: Int? = null,  // For group stage matches
    val status: String,
    @SerialName("scheduled_time") val scheduledTime: String? = null,
    @SerialName("court_number") val courtNumber: Int? = null,
    @SerialName("is_bye") val isBye: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Player info for bracket response
 */
@Serializable
data class BracketPlayerInfo(
    val uid: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null
)

/**
 * Combined response for GET bracket endpoint
 */
@Serializable
data class BracketWithMatchesResponse(
    val bracket: BracketResponse,
    val matches: List<MatchResponse>,
    val standings: List<StandingEntry> = emptyList(),
    val players: List<BracketPlayerInfo> = emptyList()
)

// ============ Internal DTOs for bracket generation ============

/**
 * Represents a team with its seed position
 */
data class TeamSeed(val teamId: String, val seed: Int)

/**
 * Represents a generated match before persistence
 */
data class GeneratedMatch(
    val roundNumber: Int,
    val matchNumber: Int,
    val roundName: String?,
    val team1Id: String?,
    val team2Id: String?,
    val isBye: Boolean,
    val status: String,
    val nextMatchNumber: Int?,     // Match number of next match (used to resolve to UUID after insert)
    val nextMatchPosition: Int?,   // 1 = team1, 2 = team2 position in next match
    val groupNumber: Int? = null   // Group number for groups_knockout format
)

// ============ Supabase Insert DTOs ============

/**
 * DTO for inserting a bracket into Supabase
 */
@Serializable
data class BracketInsertRequest(
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("category_id") val categoryId: Int,
    val format: String,
    val status: String,
    @SerialName("seeding_method") val seedingMethod: String
)

/**
 * DTO for inserting a match into Supabase
 */
@Serializable
data class MatchInsertRequest(
    @SerialName("bracket_id") val bracketId: String,
    @SerialName("round_number") val roundNumber: Int,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("round_name") val roundName: String? = null,
    @SerialName("team1_id") val team1Id: String? = null,
    @SerialName("team2_id") val team2Id: String? = null,
    val status: String,
    @SerialName("is_bye") val isBye: Boolean = false,
    @SerialName("next_match_id") val nextMatchId: String? = null,
    @SerialName("next_match_position") val nextMatchPosition: Int? = null,
    @SerialName("group_number") val groupNumber: Int? = null
)

// ============ Score DTOs ============

/**
 * Request to update match score
 */
@Serializable
data class UpdateScoreRequest(
    val sets: List<SetScore>
)

/**
 * Score for a single set
 */
@Serializable
data class SetScore(
    val team1: Int,
    val team2: Int,
    val tiebreak: TiebreakScore? = null  // Only for 7-6 sets
)

/**
 * Tiebreak score within a set
 */
@Serializable
data class TiebreakScore(
    val team1: Int,
    val team2: Int
)

/**
 * Request to manually advance a winner (optional, score-based advancement is automatic)
 */
@Serializable
data class AdvanceWinnerRequest(
    val winnerTeam: Int  // 1 or 2
)

// ============ Score Validation ============

/**
 * Result of validating a padel match score
 */
sealed class ScoreValidationResult {
    data class Valid(val winner: Int, val setsWon: Pair<Int, Int>) : ScoreValidationResult()
    data class Invalid(val message: String) : ScoreValidationResult()
}

// ============ Standings DTOs ============

/**
 * Response DTO matching tournament_standings table
 */
@Serializable
data class StandingEntry(
    val id: String,
    @SerialName("bracket_id") val bracketId: String,
    @SerialName("player_id") val playerId: String? = null,  // For individual tournaments
    @SerialName("team_id") val teamId: String? = null,      // For team tournaments
    val position: Int,
    @SerialName("total_points") val totalPoints: Int = 0,
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    @SerialName("matches_won") val matchesWon: Int = 0,
    @SerialName("matches_lost") val matchesLost: Int = 0,
    @SerialName("games_won") val gamesWon: Int = 0,
    @SerialName("games_lost") val gamesLost: Int = 0,
    @SerialName("point_difference") val pointDifference: Int = 0,
    @SerialName("group_number") val groupNumber: Int? = null,  // For group stage
    @SerialName("round_reached") val roundReached: String? = null,  // "Winner", "Finalist", "Semi-finalist", etc.
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Combined response for standings endpoint
 */
@Serializable
data class StandingsResponse(
    @SerialName("bracket_id") val bracketId: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("category_id") val categoryId: Int,
    val standings: List<StandingEntry>
)

/**
 * Internal DTO for creating/updating standings
 */
data class StandingInput(
    val bracketId: String,
    val teamId: String?,
    val playerId: String?,
    val position: Int,
    val totalPoints: Int,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val matchesLost: Int,
    val gamesWon: Int,
    val gamesLost: Int,
    val pointDifference: Int,
    val roundReached: String?,
    val groupNumber: Int? = null
)

/**
 * Serializable DTO for inserting standings to Supabase
 */
@Serializable
data class StandingInsertRequest(
    @SerialName("bracket_id") val bracketId: String,
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("player_id") val playerId: String? = null,
    val position: Int,
    @SerialName("total_points") val totalPoints: Int,
    @SerialName("matches_played") val matchesPlayed: Int,
    @SerialName("matches_won") val matchesWon: Int,
    @SerialName("matches_lost") val matchesLost: Int,
    @SerialName("games_won") val gamesWon: Int,
    @SerialName("games_lost") val gamesLost: Int,
    @SerialName("point_difference") val pointDifference: Int,
    @SerialName("round_reached") val roundReached: String? = null,
    @SerialName("group_number") val groupNumber: Int? = null
)

// ============ Status Update DTOs ============

/**
 * Request to update match status without changing score
 */
@Serializable
data class UpdateStatusRequest(
    val status: String
)

// ============ Withdrawal DTOs ============

/**
 * Request to withdraw a team from the tournament
 */
@Serializable
data class WithdrawTeamRequest(
    @SerialName("team_id") val teamId: String,
    val reason: String? = null
)

/**
 * Response after withdrawing a team
 */
@Serializable
data class WithdrawTeamResponse(
    @SerialName("forfeited_matches") val forfeitedMatches: List<String>,
    val message: String
)

// ============ Groups + Knockout DTOs ============

/**
 * Configuration for groups + knockout format
 */
@Serializable
data class GroupsKnockoutConfig(
    @SerialName("group_count") val groupCount: Int,           // Number of groups (2, 4, 8)
    @SerialName("teams_per_group") val teamsPerGroup: Int,    // Teams per group (3, 4, 5)
    @SerialName("advancing_per_group") val advancingPerGroup: Int,  // How many advance (1, 2)
    @SerialName("third_place_match") val thirdPlaceMatch: Boolean = false
)

/**
 * Assignment of teams to a single group
 */
@Serializable
data class GroupAssignment(
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("team_ids") val teamIds: List<String>  // Teams in seed order
)

/**
 * Request to assign all teams to groups
 */
@Serializable
data class AssignGroupsRequest(
    val groups: List<GroupAssignment>,
    val config: GroupsKnockoutConfig
)

/**
 * Request to swap two teams between groups
 */
@Serializable
data class SwapTeamsRequest(
    @SerialName("team1_id") val team1Id: String,
    @SerialName("team2_id") val team2Id: String
)

/**
 * Group stage state for a single group
 */
@Serializable
data class GroupState(
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("group_name") val groupName: String,
    @SerialName("team_ids") val teamIds: List<String>,
    val matches: List<MatchResponse>,
    val standings: List<StandingEntry>
)

/**
 * Response containing all groups state
 */
@Serializable
data class GroupsStateResponse(
    @SerialName("bracket_id") val bracketId: String,
    val config: GroupsKnockoutConfig,
    val groups: List<GroupState>,
    @SerialName("phase") val phase: String,  // "groups" or "knockout"
    @SerialName("knockout_generated") val knockoutGenerated: Boolean
)
