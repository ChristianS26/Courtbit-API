package services.bracket

import kotlinx.serialization.json.Json
import models.bracket.BracketResponse
import models.bracket.GroupsKnockoutConfig
import models.bracket.MatchFormatConfig
import models.bracket.MatchResponse
import models.bracket.MatchScoreResult
import models.bracket.ScoreValidationResult
import models.bracket.SetScore
import org.slf4j.LoggerFactory
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository

/**
 * Service for bracket match scoring operations.
 */
class BracketScoringService(
    private val repository: BracketRepository,
    private val json: Json,
    private val auditLog: BracketAuditRepository,
    private val standingsService: BracketStandingsService
) {
    private val logger = LoggerFactory.getLogger(BracketScoringService::class.java)

    companion object {
        const val MAX_SETS_PER_MATCH = 5
    }

    /**
     * Parse match format from bracket config. Returns null if config is missing or unparseable.
     */
    private fun parseMatchFormat(bracket: BracketResponse): MatchFormatConfig? {
        return bracket.config?.let {
            try {
                val config = json.decodeFromString<GroupsKnockoutConfig>(it.toString())
                config.matchFormat
            } catch (e: Exception) {
                logger.warn("Failed to parse bracket config for bracket ${bracket.id}: ${e.message}")
                null
            }
        }
    }

    /**
     * Validate set scores based on match format (express or classic).
     */
    private fun validateSetScores(setScores: List<SetScore>, matchFormat: MatchFormatConfig?): ScoreValidationResult {
        return if (matchFormat?.pointsPerSet != null) {
            PadelScoreValidator.validateExpressScore(setScores, matchFormat.pointsPerSet, matchFormat.sets)
        } else {
            val gamesPerSet = matchFormat?.gamesPerSet ?: 6
            val totalSets = matchFormat?.sets ?: 3
            val allowTiebreak = matchFormat?.tieBreak == true || (matchFormat?.tieBreakAt != null && matchFormat.tieBreakAt > 0)
            PadelScoreValidator.validateMatchScore(setScores, gamesPerSet, totalSets, allowTiebreak)
        }
    }

    /**
     * Recalculate standings based on bracket format.
     */
    private suspend fun recalculateStandings(bracket: BracketResponse) {
        when (bracket.format) {
            "groups_knockout" -> standingsService.calculateGroupStandings(bracket.tournamentId, bracket.categoryId)
            else -> standingsService.calculateStandings(bracket.tournamentId, bracket.categoryId)
        }
    }

    /**
     * Update match score with padel validation.
     * Validates the score according to the bracket's format (classic or express).
     * Automatically updates standings for group stage matches.
     */
    suspend fun updateMatchScore(
        matchId: String,
        setScores: List<SetScore>,
        expectedVersion: Int? = null,
        organizerId: String? = null
    ): Result<MatchScoreResult> {
        // Validate set count
        if (setScores.size > MAX_SETS_PER_MATCH) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_SETS_PER_MATCH sets per match"))
        }
        if (setScores.isEmpty()) {
            return Result.failure(IllegalArgumentException("At least 1 set required"))
        }

        // Get match to find its bracket
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        // Get bracket config to determine format
        val bracket = repository.getBracketById(match.bracketId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val matchFormat = parseMatchFormat(bracket)
        val validation = validateSetScores(setScores, matchFormat)

        if (validation is ScoreValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }

        val validResult = validation as ScoreValidationResult.Valid
        val setsWonTeam1 = validResult.setsWon.first
        val setsWonTeam2 = validResult.setsWon.second

        // Atomically update score and advance winner in a single DB transaction
        val atomicResult = repository.updateMatchScoreAndAdvance(
            matchId = matchId,
            scoreTeam1 = setsWonTeam1,
            scoreTeam2 = setsWonTeam2,
            setScores = setScores,
            winnerTeam = validResult.winner,
            expectedVersion = expectedVersion
        )

        val warnings = mutableListOf<String>()

        if (atomicResult.isSuccess) {
            auditLog.log("match", matchId, "update_score", organizerId, mapOf(
                "set_scores" to setScores.toString(),
                "winner" to validResult.winner.toString()
            ))
            recalculateStandings(bracket)
        }

        return atomicResult.map { (updatedMatch, _) -> MatchScoreResult(match = updatedMatch, warnings = warnings) }
    }

    /**
     * Advance the winner of a completed match to the next match.
     * The match must be completed with a winner determined.
     */
    suspend fun advanceWinner(matchId: String): Result<MatchResponse> {
        // Get the match to check it's completed
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalStateException("Match not found"))

        if (match.status != "completed") {
            return Result.failure(IllegalArgumentException("Cannot advance - match is not completed"))
        }

        val winnerTeam = match.winnerTeam
            ?: return Result.failure(IllegalArgumentException("Cannot advance - no winner determined"))

        // Get the winning team's ID
        val winnerTeamId = when (winnerTeam) {
            1 -> match.team1Id ?: return Result.failure(IllegalArgumentException("Team 1 ID missing"))
            2 -> match.team2Id ?: return Result.failure(IllegalArgumentException("Team 2 ID missing"))
            else -> return Result.failure(IllegalArgumentException("Invalid winner_team value: $winnerTeam"))
        }

        // Advance to next match
        repository.advanceWinner(matchId, winnerTeamId).getOrElse {
            return Result.failure(it)
        }

        // Return updated match
        return repository.getMatch(matchId)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Match not found after advancement"))
    }

    /**
     * Reset a match score: clears scores, reverses advancement, recalculates standings.
     * Uses an atomic RPC to prevent TOCTOU race conditions on the next match status check.
     */
    suspend fun resetMatchScore(matchId: String, organizerId: String? = null): Result<MatchResponse> {
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        // Atomically reset score and reverse advancement in a single DB transaction
        val result = repository.resetMatchScoreAtomic(matchId)

        if (result.isSuccess) {
            auditLog.log("match", matchId, "reset_score", organizerId, mapOf(
                "previous_score" to "${match.scoreTeam1}-${match.scoreTeam2}"
            ))
            val bracket = repository.getBracketById(match.bracketId)
            if (bracket != null) {
                recalculateStandings(bracket)
            }
        }

        return result
    }

    /**
     * Submit a score as a player (not organizer).
     * Validates that the player is in the match and the tournament allows player scores.
     */
    suspend fun submitPlayerScore(
        matchId: String,
        userId: String,
        setScores: List<SetScore>,
        expectedVersion: Int? = null
    ): Result<MatchScoreResult> {
        // Get match
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        // Check match is not already completed
        if (match.status == "completed" || match.status == "forfeit") {
            return Result.failure(IllegalArgumentException("Match is already completed"))
        }

        // Get bracket to find tournament
        val bracket = repository.getBracketById(match.bracketId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        // Check tournament allows player scores
        val allowPlayerScores = repository.getTournamentAllowPlayerScores(bracket.tournamentId)
        if (!allowPlayerScores) {
            return Result.failure(IllegalAccessException("Tournament does not allow player score submission"))
        }

        // Ensure both teams are assigned (prevents scoring BYE matches or unassigned slots)
        if (match.team1Id == null || match.team2Id == null) {
            return Result.failure(IllegalStateException("Cannot submit score: match has unassigned teams"))
        }

        // Verify player is in the match
        val team1Uids = repository.getTeamPlayerUids(match.team1Id)
        val team2Uids = repository.getTeamPlayerUids(match.team2Id)
        val allPlayerUids = team1Uids + team2Uids
        if (userId !in allPlayerUids) {
            return Result.failure(IllegalAccessException("User is not a player in this match"))
        }

        val matchFormat = parseMatchFormat(bracket)
        val validation = validateSetScores(setScores, matchFormat)

        if (validation is ScoreValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }

        val validResult = validation as ScoreValidationResult.Valid
        val setsWonTeam1 = validResult.setsWon.first
        val setsWonTeam2 = validResult.setsWon.second

        // Atomically update score and advance winner in a single DB transaction
        val atomicResult = repository.updateMatchScoreAndAdvance(
            matchId = matchId,
            scoreTeam1 = setsWonTeam1,
            scoreTeam2 = setsWonTeam2,
            setScores = setScores,
            winnerTeam = validResult.winner,
            expectedVersion = expectedVersion,
            submittedByUserId = userId
        )

        val warnings = mutableListOf<String>()

        if (atomicResult.isSuccess) {
            recalculateStandings(bracket)
        }

        return atomicResult.map { (updatedMatch, _) -> MatchScoreResult(match = updatedMatch, warnings = warnings) }
    }
}
