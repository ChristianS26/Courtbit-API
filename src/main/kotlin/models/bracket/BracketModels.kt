package models.bracket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val config: String? = null,
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
    @SerialName("score_team1") val scoreTeam1: Int? = null,
    @SerialName("score_team2") val scoreTeam2: Int? = null,
    @SerialName("set_scores") val setScores: String? = null,  // JSON string
    @SerialName("winner_team") val winnerTeam: Int? = null,
    @SerialName("next_match_id") val nextMatchId: String? = null,
    @SerialName("next_match_position") val nextMatchPosition: Int? = null,
    @SerialName("loser_next_match_id") val loserNextMatchId: String? = null,
    val status: String,
    @SerialName("scheduled_time") val scheduledTime: String? = null,
    @SerialName("court_number") val courtNumber: Int? = null,
    @SerialName("is_bye") val isBye: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Combined response for GET bracket endpoint
 */
@Serializable
data class BracketWithMatchesResponse(
    val bracket: BracketResponse,
    val matches: List<MatchResponse>
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
    val nextMatchPosition: Int?    // 1 = team1, 2 = team2 position in next match
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
    @SerialName("next_match_position") val nextMatchPosition: Int? = null
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
