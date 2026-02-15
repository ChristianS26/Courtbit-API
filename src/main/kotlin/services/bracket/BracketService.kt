package services.bracket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import models.bracket.AssignGroupsRequest
import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.GeneratedMatch
import models.bracket.GroupAssignment
import models.bracket.GroupsKnockoutConfig
import models.bracket.MatchFormatConfig
import models.bracket.GroupsStateResponse
import models.bracket.GroupState
import models.bracket.MatchResponse
import models.bracket.MatchScoreResult
import models.bracket.ScoreValidationResult
import models.bracket.SetScore
import models.bracket.StandingEntry
import models.bracket.StandingInput
import models.bracket.StandingsResponse
import models.bracket.TeamSeed
import models.bracket.WithdrawTeamResponse
import org.slf4j.LoggerFactory
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository

/**
 * Padel score validation supporting both classic and express formats.
 *
 * Classic format (FIP official rules):
 * - 6-0, 6-1, 6-2, 6-3, 6-4 (standard win)
 * - 7-5 (win from 5-5)
 * - 7-6 (tiebreak win at 6-6)
 *
 * Express format (points-based):
 * - First to N points wins (e.g., 8-0 through 8-7 for 8-point format)
 */
object PadelScoreValidator {

    /**
     * Generate valid winning scores for a given gamesPerSet (G).
     * G-0, G-1, ..., G-(G-2), (G+1)-(G-1), (G+1)-G
     */
    private fun getValidWinningScores(gamesPerSet: Int, allowTiebreak: Boolean = true): List<Pair<Int, Int>> {
        val scores = mutableListOf<Pair<Int, Int>>()
        // Regular wins: G-0 through G-(G-2)
        for (loser in 0..(gamesPerSet - 2)) {
            scores.add(gamesPerSet to loser)
        }
        if (allowTiebreak) {
            // Extended: (G+1)-(G-1)
            scores.add((gamesPerSet + 1) to (gamesPerSet - 1))
            // Tiebreak: (G+1)-G
            scores.add((gamesPerSet + 1) to gamesPerSet)
        }
        return scores
    }

    private fun getValidSetScores(gamesPerSet: Int, allowTiebreak: Boolean = true): Set<Pair<Int, Int>> = buildSet {
        getValidWinningScores(gamesPerSet, allowTiebreak).forEach { (a, b) ->
            add(a to b)  // Team 1 wins
            add(b to a)  // Team 2 wins
        }
    }

    fun isValidSetScore(team1Games: Int, team2Games: Int, gamesPerSet: Int = 6, allowTiebreak: Boolean = true): Boolean {
        return (team1Games to team2Games) in getValidSetScores(gamesPerSet, allowTiebreak)
    }

    private fun isTiebreakScore(team1: Int, team2: Int, gamesPerSet: Int): Boolean {
        return (team1 == gamesPerSet + 1 && team2 == gamesPerSet) ||
               (team1 == gamesPerSet && team2 == gamesPerSet + 1)
    }

    /**
     * Validate express format score (points-based).
     * One team must reach maxPoints, the other must be less than maxPoints.
     */
    fun validateExpressScore(setScores: List<SetScore>, maxPoints: Int, totalSets: Int): ScoreValidationResult {
        if (setScores.isEmpty()) {
            return ScoreValidationResult.Invalid("At least one set required")
        }
        if (setScores.size > totalSets) {
            return ScoreValidationResult.Invalid("Maximum $totalSets set(s) allowed")
        }

        var team1SetsWon = 0
        var team2SetsWon = 0
        val setsNeededToWin = (totalSets + 1) / 2  // Ceiling division for best-of-N

        for ((index, set) in setScores.withIndex()) {
            // Validate express score: one team must reach maxPoints exactly
            val team1Wins = set.team1 == maxPoints && set.team2 < maxPoints
            val team2Wins = set.team2 == maxPoints && set.team1 < maxPoints

            if (!team1Wins && !team2Wins) {
                // Check if it's a valid incomplete set or invalid score
                if (set.team1 > maxPoints || set.team2 > maxPoints) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1}: Maximum score is $maxPoints points"
                    )
                }
                if (set.team1 == maxPoints && set.team2 == maxPoints) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1}: Both teams cannot have $maxPoints points"
                    )
                }
                return ScoreValidationResult.Invalid(
                    "Set ${index + 1}: One team must reach $maxPoints points to win"
                )
            }

            // Determine set winner
            if (team1Wins) team1SetsWon++
            if (team2Wins) team2SetsWon++

            // Check if match is already decided
            if (team1SetsWon >= setsNeededToWin || team2SetsWon >= setsNeededToWin) {
                if (index < setScores.size - 1) {
                    return ScoreValidationResult.Invalid(
                        "Match already decided after set ${index + 1}, but ${setScores.size} sets provided"
                    )
                }
            }
        }

        // Verify match is complete
        return when {
            team1SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 1, setsWon = team1SetsWon to team2SetsWon)
            team2SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 2, setsWon = team1SetsWon to team2SetsWon)
            else -> ScoreValidationResult.Invalid("Match incomplete: need $setsNeededToWin set(s) to win (current: $team1SetsWon-$team2SetsWon)")
        }
    }

    /**
     * Validate classic format score (games-based).
     * @param gamesPerSet configurable games per set (default 6)
     * @param totalSets configurable total sets (default 3, best-of)
     */
    fun validateMatchScore(setScores: List<SetScore>, gamesPerSet: Int = 6, totalSets: Int = 3, allowTiebreak: Boolean = true): ScoreValidationResult {
        if (setScores.isEmpty()) {
            return ScoreValidationResult.Invalid("At least one set required")
        }
        if (setScores.size > totalSets) {
            return ScoreValidationResult.Invalid("Maximum $totalSets sets allowed")
        }

        val setsNeededToWin = (totalSets + 1) / 2
        var team1SetsWon = 0
        var team2SetsWon = 0

        for ((index, set) in setScores.withIndex()) {
            if (!isValidSetScore(set.team1, set.team2, gamesPerSet, allowTiebreak)) {
                val validScores = getValidWinningScores(gamesPerSet, allowTiebreak).joinToString(", ") { "${it.first}-${it.second}" }
                return ScoreValidationResult.Invalid(
                    "Invalid set ${index + 1} score: ${set.team1}-${set.team2}. " +
                    "Valid scores: $validScores"
                )
            }

            // Validate tiebreak if (G+1)-G
            if (isTiebreakScore(set.team1, set.team2, gamesPerSet)) {
                if (set.tiebreak == null) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1} is a tiebreak (${gamesPerSet + 1}-${gamesPerSet}), tiebreak score required"
                    )
                }
                // Tiebreak validation: first to 7 with 2-point lead
                val tb = set.tiebreak
                val winner = maxOf(tb.team1, tb.team2)
                val loser = minOf(tb.team1, tb.team2)
                if (winner < 7 || (winner - loser) < 2) {
                    return ScoreValidationResult.Invalid(
                        "Invalid tiebreak score: ${tb.team1}-${tb.team2}. " +
                        "Must be first to 7 with 2-point lead"
                    )
                }
            }

            // Determine set winner
            when {
                set.team1 > set.team2 -> team1SetsWon++
                set.team2 > set.team1 -> team2SetsWon++
            }

            // Check if match is already decided
            if (team1SetsWon >= setsNeededToWin || team2SetsWon >= setsNeededToWin) {
                if (index < setScores.size - 1) {
                    return ScoreValidationResult.Invalid(
                        "Match already decided after set ${index + 1}, but ${setScores.size} sets provided"
                    )
                }
            }
        }

        // Verify match is complete
        return when {
            team1SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 1, setsWon = team1SetsWon to team2SetsWon)
            team2SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 2, setsWon = team1SetsWon to team2SetsWon)
            else -> ScoreValidationResult.Invalid("Match incomplete: need $setsNeededToWin set(s) to win (current: $team1SetsWon-$team2SetsWon)")
        }
    }
}

/**
 * Service for bracket operations including generation algorithm
 */
class BracketService(
    private val repository: BracketRepository,
    private val json: Json,
    private val auditLog: BracketAuditRepository
) {
    private val logger = LoggerFactory.getLogger(BracketService::class.java)

    companion object {
        const val MAX_TEAMS_PER_BRACKET = 128
        const val MAX_GROUPS = 16
        const val MAX_SETS_PER_MATCH = 5
    }

    // ============ Private Helpers ============

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
            "groups_knockout" -> calculateGroupStandings(bracket.tournamentId, bracket.categoryId)
            "round_robin" -> calculateStandings(bracket.tournamentId, bracket.categoryId)
        }
    }

    /**
     * Get bracket with all matches for a tournament category
     */
    suspend fun getBracket(tournamentId: String, categoryId: Int): BracketWithMatchesResponse? {
        return repository.getBracketWithMatches(tournamentId, categoryId)
    }

    /**
     * Get all brackets for a tournament
     */
    suspend fun getBracketsByTournament(tournamentId: String): List<BracketResponse> {
        return repository.getBracketsByTournament(tournamentId)
    }

    /**
     * Get all brackets with matches, standings, and players for a tournament (bulk fetch)
     */
    suspend fun getAllBracketsWithMatches(tournamentId: String): List<BracketWithMatchesResponse> {
        return repository.getAllBracketsWithMatches(tournamentId)
    }

    /**
     * Update bracket config (e.g. match format) without regenerating matches.
     */
    suspend fun updateBracketConfig(
        tournamentId: String,
        categoryId: Int,
        configJson: String
    ): Result<BracketWithMatchesResponse> {
        val existing = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val updated = repository.updateBracketConfig(existing.bracket.id, configJson)
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to update bracket config"))
        }

        // Re-fetch to return updated data
        val refreshed = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found after update"))

        return Result.success(refreshed)
    }

    /**
     * Create a bracket without generating matches.
     * Allows organizer to configure the bracket before generating matches.
     * If bracket exists, deletes it and creates a new one.
     */
    suspend fun createBracket(
        tournamentId: String,
        categoryId: Int,
        format: String,
        seedingMethod: String
    ): Result<BracketResponse> {
        // Check if bracket already exists - if so, delete it
        val existing = repository.getBracket(tournamentId, categoryId)
        if (existing != null) {
            repository.deleteBracket(existing.id)
        }

        // Create bracket record
        val bracket = repository.createBracket(
            tournamentId = tournamentId,
            categoryId = categoryId,
            format = format,
            seedingMethod = seedingMethod
        ) ?: return Result.failure(IllegalStateException("Failed to create bracket record"))

        return Result.success(bracket)
    }

    /**
     * Generate matches for a bracket.
     * Uses existing bracket if available, or creates a new one.
     * Deletes existing matches and regenerates them.
     */
    suspend fun generateBracket(
        tournamentId: String,
        categoryId: Int,
        seedingMethod: String,
        teamIds: List<String>
    ): Result<BracketWithMatchesResponse> {
        // Validate input
        if (teamIds.size < 2) {
            return Result.failure(IllegalArgumentException("At least 2 teams required to generate bracket"))
        }
        if (teamIds.size > MAX_TEAMS_PER_BRACKET) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_TEAMS_PER_BRACKET teams per bracket"))
        }

        // Check if bracket already exists
        val existing = repository.getBracket(tournamentId, categoryId)
        val bracket: BracketResponse

        if (existing != null) {
            // Use existing bracket, but delete its matches to regenerate
            repository.deleteMatchesByBracketId(existing.id)
            bracket = existing
        } else {
            // Create new bracket record
            bracket = repository.createBracket(
                tournamentId = tournamentId,
                categoryId = categoryId,
                format = "knockout",
                seedingMethod = seedingMethod
            ) ?: return Result.failure(IllegalStateException("Failed to create bracket record"))
        }

        // Generate match structure
        val teams = teamIds.mapIndexed { index, teamId ->
            TeamSeed(teamId = teamId, seed = index + 1)
        }

        val generatedMatches = generateKnockoutMatches(teams)

        // Create matches in database
        val createdMatches = repository.createMatches(bracket.id, generatedMatches)
        if (createdMatches.isEmpty() && generatedMatches.isNotEmpty()) {
            // Rollback: delete the bracket
            repository.deleteBracket(bracket.id)
            return Result.failure(IllegalStateException("Failed to create matches"))
        }

        // Build match number to UUID mapping
        val matchNumberToId = createdMatches.associate { it.matchNumber to it.id }

        // Update next_match_id references
        for (generated in generatedMatches) {
            if (generated.nextMatchNumber != null && generated.nextMatchPosition != null) {
                val matchId = matchNumberToId[generated.matchNumber] ?: continue
                val nextMatchId = matchNumberToId[generated.nextMatchNumber] ?: continue
                repository.updateMatchNextMatchId(matchId, nextMatchId, generated.nextMatchPosition)
            }
        }

        // Auto-advance bye matches: mark as completed and advance winner to next round
        processByeMatches(createdMatches, matchNumberToId)

        // Fetch complete bracket with updated matches
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch created bracket"))

        return Result.success(result)
    }

    /**
     * Publish a bracket, locking its structure
     */
    suspend fun publishBracket(tournamentId: String, categoryId: Int, organizerId: String? = null): Result<BracketResponse> {
        val bracket = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        if (bracket.status == "published") {
            return Result.failure(IllegalStateException("Bracket is already published"))
        }

        val updated = repository.updateBracketStatus(bracket.id, "published")
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to publish bracket"))
        }

        auditLog.log("bracket", bracket.id, "publish", organizerId)

        // Return updated bracket
        val result = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Unpublish a bracket, reverting to in_progress status
     */
    suspend fun unpublishBracket(tournamentId: String, categoryId: Int, organizerId: String? = null): Result<BracketResponse> {
        val bracket = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        if (bracket.status != "published") {
            return Result.failure(IllegalStateException("Bracket is not published"))
        }

        val updated = repository.updateBracketStatus(bracket.id, "in_progress")
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to unpublish bracket"))
        }

        auditLog.log("bracket", bracket.id, "unpublish", organizerId)

        // Return updated bracket
        val result = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Delete a bracket. Only allowed if no matches have been started or completed.
     */
    suspend fun deleteBracket(bracketId: String, organizerId: String? = null): Result<Boolean> {
        // Check if any matches have been played
        val bracketData = repository.getBracketById(bracketId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val matches = repository.getMatchesByBracketId(bracketId)
        val playedMatches = matches.count { it.status == "in_progress" || it.status == "completed" }

        if (playedMatches > 0) {
            return Result.failure(IllegalStateException(
                "Cannot delete bracket: $playedMatches match(es) already started or completed. " +
                "Delete is only allowed when no matches have been played."
            ))
        }

        val deleted = repository.deleteBracket(bracketId)
        if (deleted) {
            auditLog.log("bracket", bracketId, "delete", organizerId)
        }
        return if (deleted) Result.success(true)
        else Result.failure(IllegalStateException("Failed to delete bracket"))
    }

    // ============ Match Scoring ============

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

    // ============ Player Score Submission ============

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

    // ============ Standings ============

    /**
     * Get existing standings for a bracket
     */
    suspend fun getStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val existingStandings = repository.getStandings(bracket.id)

        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = existingStandings
        ))
    }

    /**
     * Calculate standings from completed matches.
     * For knockout brackets, standings are based on how far each team progressed:
     * - Winner: Position 1 (100 points)
     * - Finalist: Position 2 (70 points)
     * - Semi-finalists: Position 3-4 (50 points)
     * - Quarter-finalists: Position 5-8 (30 points)
     * - etc.
     */
    suspend fun calculateStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Parse config for configurable points
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        }
        val kp = config?.knockoutPoints ?: GroupsKnockoutConfig.KnockoutPointsConfig()

        // Calculate from completed and forfeited matches
        val completedMatches = matches.filter { it.status in listOf("completed", "forfeit") }

        if (completedMatches.isEmpty()) {
            return Result.failure(IllegalArgumentException("No completed matches to calculate standings"))
        }

        // Build standings from match results
        val standingsMap = mutableMapOf<String, StandingBuilder>()

        // Process each completed match
        for (match in completedMatches) {
            val team1Id = match.team1Id ?: continue
            val team2Id = match.team2Id ?: continue

            // Initialize entries if not exists
            if (team1Id !in standingsMap) {
                standingsMap[team1Id] = StandingBuilder(teamId = team1Id)
            }
            if (team2Id !in standingsMap) {
                standingsMap[team2Id] = StandingBuilder(teamId = team2Id)
            }

            // Get set scores for game counts
            val setScores = match.setScores?.let {
                try { json.decodeFromJsonElement<List<SetScore>>(it) }
                catch (e: Exception) { null }
            } ?: emptyList()

            val team1Games = setScores.sumOf { it.team1 }
            val team2Games = setScores.sumOf { it.team2 }

            // Update stats
            standingsMap[team1Id]!!.apply {
                matchesPlayed++
                gamesWon += team1Games
                gamesLost += team2Games
                if (match.winnerTeam == 1) {
                    matchesWon++
                } else {
                    matchesLost++
                    lastRoundLost = match.roundNumber
                    roundNameLost = match.roundName
                }
            }

            standingsMap[team2Id]!!.apply {
                matchesPlayed++
                gamesWon += team2Games
                gamesLost += team1Games
                if (match.winnerTeam == 2) {
                    matchesWon++
                } else {
                    matchesLost++
                    lastRoundLost = match.roundNumber
                    roundNameLost = match.roundName
                }
            }
        }

        // Find the winner (team that won all matches)
        val totalRounds = matches.maxOfOrNull { it.roundNumber } ?: 1
        val finalMatch = matches.find { it.roundNumber == totalRounds && it.status == "completed" }

        // Determine positions based on round reached
        // Teams that lost later rank higher, winner ranks first
        val standings = standingsMap.values.sortedWith(
            compareByDescending<StandingBuilder> { it.matchesLost == 0 && finalMatch != null }  // Winner first
                .thenByDescending { it.lastRoundLost ?: Int.MAX_VALUE }  // Lost later = better
                .thenByDescending { it.gamesWon - it.gamesLost }  // Tiebreaker: game difference
        ).mapIndexed { index, builder ->
            val roundReached = when {
                builder.matchesLost == 0 && finalMatch != null -> "Campeón"
                builder.roundNameLost == "Final" -> "Finalista"
                builder.roundNameLost == "Semifinales" -> "Semifinalista"
                builder.roundNameLost == "Cuartos de final" -> "Cuartofinalista"
                else -> "Ronda ${builder.lastRoundLost ?: 1}"
            }

            val points = when (roundReached) {
                "Campeón" -> kp.winner
                "Finalista" -> kp.finalist
                "Semifinalista" -> kp.semiFinalist
                "Cuartofinalista" -> kp.quarterFinalist
                else -> kp.basePoints + ((builder.lastRoundLost ?: 1) * kp.perRoundBonus)
            }

            StandingInput(
                bracketId = bracket.id,
                teamId = builder.teamId,
                playerId = null,
                position = index + 1,
                totalPoints = points,
                matchesPlayed = builder.matchesPlayed,
                matchesWon = builder.matchesWon,
                matchesLost = builder.matchesLost,
                gamesWon = builder.gamesWon,
                gamesLost = builder.gamesLost,
                pointDifference = builder.gamesWon - builder.gamesLost,
                roundReached = roundReached
            )
        }

        // Persist standings
        repository.upsertStandings(standings)

        // Return calculated standings
        val savedStandings = repository.getStandings(bracket.id)
        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = savedStandings
        ))
    }

    /**
     * Helper class for building standings during calculation
     */
    private data class StandingBuilder(
        val teamId: String,
        var matchesPlayed: Int = 0,
        var matchesWon: Int = 0,
        var matchesLost: Int = 0,
        var gamesWon: Int = 0,
        var gamesLost: Int = 0,
        var lastRoundLost: Int? = null,
        var roundNameLost: String? = null
    )

    // ============ Groups + Knockout Generation ============

    /**
     * Generate group stage matches for groups_knockout format.
     * Creates round-robin matches within each group.
     */
    suspend fun generateGroupStage(
        tournamentId: String,
        categoryId: Int,
        request: AssignGroupsRequest
    ): Result<BracketWithMatchesResponse> {
        val config = request.config
        val groups = request.groups

        // Validate input
        if (groups.size != config.groupCount) {
            return Result.failure(IllegalArgumentException(
                "Expected ${config.groupCount} groups, got ${groups.size}"
            ))
        }
        if (config.groupCount > MAX_GROUPS) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_GROUPS groups allowed"))
        }
        val totalTeams = groups.sumOf { it.teamIds.size }
        if (totalTeams > MAX_TEAMS_PER_BRACKET) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_TEAMS_PER_BRACKET teams per bracket"))
        }

        // Validate match format config if provided
        config.matchFormat?.let { format ->
            if (format.pointsPerSet != null && format.pointsPerSet <= 0) {
                return Result.failure(IllegalArgumentException("pointsPerSet must be greater than 0"))
            }
            if (format.gamesPerSet != null && format.gamesPerSet <= 0) {
                return Result.failure(IllegalArgumentException("gamesPerSet must be greater than 0"))
            }
            if (format.sets <= 0 || format.sets > MAX_SETS_PER_MATCH) {
                return Result.failure(IllegalArgumentException("sets must be between 1 and $MAX_SETS_PER_MATCH"))
            }
        }

        // Validate each group has at least 2 teams (allow uneven groups)
        for (group in groups) {
            if (group.teamIds.size < 2) {
                return Result.failure(IllegalArgumentException(
                    "Group ${group.groupNumber} has ${group.teamIds.size} teams, minimum is 2"
                ))
            }
        }

        // Check if bracket already exists
        val existing = repository.getBracket(tournamentId, categoryId)
        val bracket: BracketResponse

        if (existing != null) {
            // Delete existing matches to regenerate
            repository.deleteMatchesByBracketId(existing.id)
            // Update bracket config
            repository.updateBracketConfig(existing.id, json.encodeToString(config))
            bracket = existing
        } else {
            // Create new bracket
            bracket = repository.createBracket(
                tournamentId = tournamentId,
                categoryId = categoryId,
                format = "groups_knockout",
                seedingMethod = "manual"
            ) ?: return Result.failure(IllegalStateException("Failed to create bracket"))
            // Save config to the new bracket
            repository.updateBracketConfig(bracket.id, json.encodeToString(config))
        }

        // Generate round-robin matches for each group
        val allMatches = mutableListOf<GeneratedMatch>()
        var matchNumber = 1

        for (group in groups) {
            val groupMatches = generateRoundRobinMatches(
                teamIds = group.teamIds,
                groupNumber = group.groupNumber,
                startMatchNumber = matchNumber
            )
            allMatches.addAll(groupMatches)
            matchNumber += groupMatches.size
        }

        // Create matches in database
        val createdMatches = repository.createMatches(bracket.id, allMatches)
        if (createdMatches.isEmpty() && allMatches.isNotEmpty()) {
            repository.deleteBracket(bracket.id)
            return Result.failure(IllegalStateException("Failed to create group matches"))
        }

        // Create initial standings for each team
        val standingInputs = groups.flatMap { group ->
            group.teamIds.mapIndexed { index, teamId ->
                StandingInput(
                    bracketId = bracket.id,
                    teamId = teamId,
                    playerId = null,
                    position = index + 1,
                    totalPoints = 0,
                    matchesPlayed = 0,
                    matchesWon = 0,
                    matchesLost = 0,
                    gamesWon = 0,
                    gamesLost = 0,
                    pointDifference = 0,
                    roundReached = null,
                    groupNumber = group.groupNumber
                )
            }
        }
        repository.upsertStandings(standingInputs)

        // Return complete bracket
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch created bracket"))

        return Result.success(result)
    }

    /**
     * Get current state of all groups
     */
    suspend fun getGroupsState(
        tournamentId: String,
        categoryId: Int
    ): Result<GroupsStateResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        if (bracket.format != "groups_knockout") {
            return Result.failure(IllegalArgumentException("Bracket is not groups_knockout format"))
        }

        // Parse config
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        } ?: GroupsKnockoutConfig(groupCount = 4, teamsPerGroup = 4, advancingPerGroup = 2)

        val matches = bracketWithMatches.matches
        val standings = bracketWithMatches.standings

        // Group matches by group_number
        val groupMatches = matches.filter { it.groupNumber != null }.groupBy { it.groupNumber!! }
        val groupStandings = standings.filter { it.groupNumber != null }.groupBy { it.groupNumber!! }

        // Check if knockout phase exists (matches without group_number)
        val knockoutMatches = matches.filter { it.groupNumber == null }
        val knockoutGenerated = knockoutMatches.isNotEmpty()

        // Determine current phase
        val allGroupMatchesComplete = groupMatches.values.flatten().all { it.status == "completed" }
        val phase = when {
            knockoutGenerated -> "knockout"
            else -> "groups"
        }

        // Build group states
        val groups = (1..config.groupCount).map { groupNum ->
            val gMatches = groupMatches[groupNum] ?: emptyList()
            val gStandings = groupStandings[groupNum] ?: emptyList()
            val teamIds = gStandings.mapNotNull { it.teamId }

            GroupState(
                groupNumber = groupNum,
                groupName = "Grupo ${('A' + groupNum - 1)}",
                teamIds = teamIds,
                matches = gMatches.sortedBy { it.matchNumber },
                standings = gStandings.sortedBy { it.position }
            )
        }

        return Result.success(GroupsStateResponse(
            bracketId = bracket.id,
            config = config,
            groups = groups,
            phase = phase,
            knockoutGenerated = knockoutGenerated
        ))
    }

    /**
     * Calculate and update group standings based on completed matches.
     */
    suspend fun calculateGroupStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Parse config for configurable points
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        }

        // Get group matches only
        val groupMatches = matches.filter { it.groupNumber != null }

        // Build standings map by team
        val standingsMap = mutableMapOf<String, GroupStandingBuilder>()

        // Initialize from existing standings to get group numbers
        val existingStandings = repository.getStandings(bracket.id)
        for (standing in existingStandings) {
            val teamId = standing.teamId ?: continue
            val groupNumber = standing.groupNumber ?: continue
            standingsMap[teamId] = GroupStandingBuilder(
                teamId = teamId,
                groupNumber = groupNumber
            )
        }

        // If no standings exist, initialize from match participants
        if (standingsMap.isEmpty() && groupMatches.isNotEmpty()) {
            for (match in groupMatches) {
                val groupNumber = match.groupNumber ?: continue
                match.team1Id?.let { teamId ->
                    if (teamId !in standingsMap) {
                        standingsMap[teamId] = GroupStandingBuilder(teamId = teamId, groupNumber = groupNumber)
                    }
                }
                match.team2Id?.let { teamId ->
                    if (teamId !in standingsMap) {
                        standingsMap[teamId] = GroupStandingBuilder(teamId = teamId, groupNumber = groupNumber)
                    }
                }
            }
        }

        // Process completed and forfeited group matches
        for (match in groupMatches.filter { it.status in listOf("completed", "forfeit") }) {
            val team1Id = match.team1Id ?: continue
            val team2Id = match.team2Id ?: continue

            // Parse set scores
            val setScores = match.setScores?.let {
                try { json.decodeFromJsonElement<List<SetScore>>(it) }
                catch (e: Exception) { null }
            } ?: emptyList()

            val team1Games = setScores.sumOf { it.team1 }
            val team2Games = setScores.sumOf { it.team2 }

            val winPoints = config?.groupWinPoints ?: 3

            // Update team1 stats
            standingsMap[team1Id]?.apply {
                matchesPlayed++
                gamesWon += team1Games
                gamesLost += team2Games
                if (match.winnerTeam == 1) {
                    matchesWon++
                    totalPoints += winPoints
                } else {
                    matchesLost++
                }
                // H2H record vs team2
                headToHead.getOrPut(team2Id) { H2HRecord() }.apply {
                    gamesFor += team1Games
                    gamesAgainst += team2Games
                    if (match.winnerTeam == 1) wins++ else losses++
                }
            }

            // Update team2 stats
            standingsMap[team2Id]?.apply {
                matchesPlayed++
                gamesWon += team2Games
                gamesLost += team1Games
                if (match.winnerTeam == 2) {
                    matchesWon++
                    totalPoints += winPoints
                } else {
                    matchesLost++
                }
                // H2H record vs team1
                headToHead.getOrPut(team1Id) { H2HRecord() }.apply {
                    gamesFor += team2Games
                    gamesAgainst += team1Games
                    if (match.winnerTeam == 2) wins++ else losses++
                }
            }
        }

        // Sort by group, then by: points → H2H → game difference → games won
        val sortedStandings = standingsMap.values
            .groupBy { it.groupNumber }
            .flatMap { (_, groupTeams) ->
                groupTeams.sortedWith(
                    Comparator<GroupStandingBuilder> { a, b ->
                        // 1. Total points (descending)
                        val pointsDiff = b.totalPoints - a.totalPoints
                        if (pointsDiff != 0) return@Comparator pointsDiff

                        // 2. Head-to-head: team with more H2H wins ranks higher
                        val aWinsVsB = a.headToHead[b.teamId]?.wins ?: 0
                        val bWinsVsA = b.headToHead[a.teamId]?.wins ?: 0
                        if (aWinsVsB != bWinsVsA) return@Comparator bWinsVsA - aWinsVsB  // descending: more wins = smaller value

                        // 3. Game difference (descending)
                        val gdDiff = (b.gamesWon - b.gamesLost) - (a.gamesWon - a.gamesLost)
                        if (gdDiff != 0) return@Comparator gdDiff

                        // 4. Games won (descending)
                        b.gamesWon - a.gamesWon
                    }
                ).mapIndexed { index, builder ->
                    StandingInput(
                        bracketId = bracket.id,
                        teamId = builder.teamId,
                        playerId = null,
                        position = index + 1,
                        totalPoints = builder.totalPoints,
                        matchesPlayed = builder.matchesPlayed,
                        matchesWon = builder.matchesWon,
                        matchesLost = builder.matchesLost,
                        gamesWon = builder.gamesWon,
                        gamesLost = builder.gamesLost,
                        pointDifference = builder.gamesWon - builder.gamesLost,
                        roundReached = null,
                        groupNumber = builder.groupNumber
                    )
                }
            }

        // Persist standings
        repository.upsertStandings(sortedStandings)

        val savedStandings = repository.getStandings(bracket.id)
        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = savedStandings
        ))
    }

    /**
     * Generate knockout phase from group stage results.
     * Takes top N teams from each group based on standings.
     */
    suspend fun generateKnockoutFromGroups(
        tournamentId: String,
        categoryId: Int,
        organizerId: String? = null
    ): Result<BracketWithMatchesResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches
        val standings = bracketWithMatches.standings

        // Parse config
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        } ?: return Result.failure(IllegalStateException("Invalid bracket config"))

        // Check if knockout already exists
        val knockoutMatches = matches.filter { it.groupNumber == null }
        if (knockoutMatches.isNotEmpty()) {
            return Result.failure(IllegalStateException("Knockout phase already generated"))
        }

        // Check all group matches are complete
        val groupMatches = matches.filter { it.groupNumber != null }
        val incompleteCount = groupMatches.count { it.status != "completed" }
        if (incompleteCount > 0) {
            return Result.failure(IllegalArgumentException(
                "Cannot generate knockout: $incompleteCount group matches still incomplete"
            ))
        }

        // Get advancing teams from each group
        var groupStandings = standings
            .filter { it.groupNumber != null }
            .groupBy { it.groupNumber!! }

        // If standings don't have group numbers, recalculate them first
        if (groupStandings.isEmpty() && groupMatches.isNotEmpty()) {
            val recalcResult = calculateGroupStandings(tournamentId, categoryId)
            if (recalcResult.isSuccess) {
                val recalcStandings = recalcResult.getOrNull()?.standings ?: emptyList()
                groupStandings = recalcStandings
                    .filter { it.groupNumber != null }
                    .groupBy { it.groupNumber!! }
            }
        }

        val advancingTeams = mutableListOf<TeamSeed>()
        val teamGroupMap = mutableMapOf<String, Int>() // teamId -> groupNumber
        var seedCounter = 1

        // Collect advancing teams by position, sorted by performance within each tier
        for (position in 1..config.advancingPerGroup) {
            val teamsAtPosition = mutableListOf<Pair<StandingEntry, Int>>() // (standing, groupNum)
            for (groupNum in 1..config.groupCount) {
                val groupTeams = groupStandings[groupNum]?.sortedBy { it.position } ?: emptyList()
                val teamAtPosition = groupTeams.getOrNull(position - 1)
                if (teamAtPosition?.teamId == null) {
                    return Result.failure(IllegalStateException(
                        "No team at position $position in group $groupNum"
                    ))
                }
                teamsAtPosition.add(teamAtPosition to groupNum)
            }

            // Within same position tier, sort by performance (points, point diff, games won)
            val sorted = teamsAtPosition.sortedWith(
                compareByDescending<Pair<StandingEntry, Int>> { it.first.totalPoints }
                    .thenByDescending { it.first.pointDifference }
                    .thenByDescending { it.first.gamesWon }
            )

            for ((standing, groupNum) in sorted) {
                val teamId = standing.teamId!!
                advancingTeams.add(TeamSeed(teamId, seedCounter++))
                teamGroupMap[teamId] = groupNum
            }
        }

        // Add wildcards (best runners-up) if configured
        val wildcardCount = config.wildcardCount
        if (wildcardCount > 0) {
            val wildcardPosition = config.advancingPerGroup + 1

            val wildcardCandidates = mutableListOf<StandingEntry>()
            for (groupNum in 1..config.groupCount) {
                val groupTeams = groupStandings[groupNum]?.sortedBy { it.position } ?: emptyList()
                val candidate = groupTeams.getOrNull(wildcardPosition - 1)
                if (candidate != null) {
                    wildcardCandidates.add(candidate)
                }
            }

            val sortedWildcards = wildcardCandidates.sortedWith(
                compareByDescending<StandingEntry> { it.totalPoints }
                    .thenByDescending { it.pointDifference }
                    .thenByDescending { it.gamesWon }
            )

            val selectedWildcards = sortedWildcards.take(wildcardCount)

            for (wildcard in selectedWildcards) {
                val teamId = wildcard.teamId ?: continue
                advancingTeams.add(TeamSeed(teamId, seedCounter++))
                teamGroupMap[teamId] = wildcard.groupNumber ?: 0
            }
        }

        // Cross-group seeding: separate same-group teams across bracket halves/quarters
        val seededTeams = placeTeamsAntiRematch(advancingTeams, teamGroupMap)

        // Get current max match number
        val maxMatchNumber = matches.maxOfOrNull { it.matchNumber } ?: 0

        // Generate knockout bracket
        val knockoutMatchesGenerated = generateKnockoutMatches(seededTeams)
            .map { it.copy(matchNumber = it.matchNumber + maxMatchNumber) }

        // Create matches
        val createdMatches = repository.createMatches(bracket.id, knockoutMatchesGenerated)
        if (createdMatches.isEmpty() && knockoutMatchesGenerated.isNotEmpty()) {
            return Result.failure(IllegalStateException("Failed to create knockout matches"))
        }

        // Build match number to UUID mapping
        val matchNumberToId = createdMatches.associate { it.matchNumber to it.id }

        // Update next_match_id references
        for (generated in knockoutMatchesGenerated) {
            if (generated.nextMatchNumber != null && generated.nextMatchPosition != null) {
                val adjustedNextMatchNumber = generated.nextMatchNumber + maxMatchNumber
                val matchId = matchNumberToId[generated.matchNumber] ?: continue
                val nextMatchId = matchNumberToId[adjustedNextMatchNumber] ?: continue
                repository.updateMatchNextMatchId(matchId, nextMatchId, generated.nextMatchPosition)
            }
        }

        // Auto-advance bye matches
        processByeMatches(createdMatches, matchNumberToId)

        auditLog.log("bracket", bracket.id, "generate_knockout", organizerId, mapOf(
            "knockout_matches" to createdMatches.size.toString(),
            "advancing_teams" to seededTeams.size.toString()
        ))

        // Return complete bracket
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Delete knockout phase matches (keeps group stage intact).
     * Allows regenerating knockout with different rules or fixing errors.
     */
    suspend fun deleteKnockoutPhase(
        tournamentId: String,
        categoryId: Int,
        organizerId: String? = null
    ): Result<Unit> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Find all knockout matches (matches with groupNumber = null)
        val knockoutMatches = matches.filter { it.groupNumber == null }

        if (knockoutMatches.isEmpty()) {
            return Result.failure(IllegalArgumentException("No knockout phase found to delete"))
        }

        // Check if any knockout match has been played
        val playedKnockoutMatches = knockoutMatches.filter { it.status in listOf("in_progress", "completed") }
        if (playedKnockoutMatches.isNotEmpty()) {
            return Result.failure(IllegalStateException(
                "Cannot delete knockout phase: ${playedKnockoutMatches.size} match(es) already started or completed"
            ))
        }

        // Delete all knockout matches
        val deletedCount = repository.deleteMatchesByIds(knockoutMatches.map { it.id })

        if (deletedCount != knockoutMatches.size) {
            return Result.failure(IllegalStateException("Failed to delete all knockout matches"))
        }

        auditLog.log("bracket", bracket.id, "delete_knockout", organizerId, mapOf(
            "deleted_matches" to deletedCount.toString()
        ))

        return Result.success(Unit)
    }

    /**
     * Swap two teams between groups (before group stage starts).
     */
    suspend fun swapTeamsInGroups(
        tournamentId: String,
        categoryId: Int,
        team1Id: String,
        team2Id: String,
        organizerId: String? = null
    ): Result<GroupsStateResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Atomically swap teams in a single DB transaction
        val swapResult = repository.swapTeamsInGroupsAtomic(bracket.id, team1Id, team2Id)
        if (swapResult.isFailure) {
            return Result.failure(swapResult.exceptionOrNull()!!)
        }

        auditLog.log("bracket", bracket.id, "swap_teams", organizerId, mapOf(
            "team1_id" to team1Id, "team2_id" to team2Id
        ))

        // Recalculate standings if any matches have been played
        val hasPlayedMatches = matches.any { it.status in listOf("completed", "forfeit") }
        if (hasPlayedMatches) {
            calculateGroupStandings(tournamentId, categoryId)
        }

        return getGroupsState(tournamentId, categoryId)
    }

    /**
     * Compute group formation: how many groups and their sizes.
     * Uses groups of 3 and 4 to minimize variance.
     */
    private fun computeGroupFormation(teamCount: Int): Pair<Int, List<Int>> {
        if (teamCount < 4) return 0 to emptyList()
        if (teamCount == 4) return 1 to listOf(4)
        if (teamCount == 5) return 1 to listOf(5)

        val remainder = teamCount % 3
        val groupsOf4 = when (remainder) { 0 -> 0; 1 -> 1; else -> 2 }
        val groupsOf3 = (teamCount - groupsOf4 * 4) / 3

        val sizes = List(groupsOf3) { 3 } + List(groupsOf4) { 4 }
        return (groupsOf3 + groupsOf4) to sizes
    }

    /**
     * Snake-seed teams into groups respecting exact group sizes.
     * Serpentine pattern alternates L→R and R→L per row.
     */
    private fun snakeSeedTeamsWithSizes(teamIds: List<String>, groupSizes: List<Int>): List<List<String>> {
        val groupCount = groupSizes.size
        if (groupCount < 1 || teamIds.isEmpty()) return emptyList()

        val groups = List(groupCount) { mutableListOf<String>() }
        var teamIndex = 0
        var row = 0

        while (teamIndex < teamIds.size) {
            val indices = (0 until groupCount).toList()
            val ordered = if (row % 2 == 0) indices else indices.reversed()

            for (gi in ordered) {
                if (teamIndex >= teamIds.size) break
                if (groups[gi].size < groupSizes[gi]) {
                    groups[gi].add(teamIds[teamIndex])
                    teamIndex++
                }
            }
            row++
        }

        return groups
    }

    /**
     * Generate group stage with server-side group computation.
     * Backend computes group formation + snake seeding from team IDs and config.
     */
    suspend fun generateGroupStageAuto(
        tournamentId: String,
        categoryId: Int,
        teamIds: List<String>,
        config: GroupsKnockoutConfig
    ): Result<BracketWithMatchesResponse> {
        // Compute group sizes from config or auto-detect
        val (_, groupSizes) = if (config.groupCount > 0 && config.teamsPerGroup > 0) {
            // Use explicit config
            val sizes = List(config.groupCount) { config.teamsPerGroup }
            config.groupCount to sizes
        } else {
            computeGroupFormation(teamIds.size)
        }

        if (groupSizes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Cannot form groups from ${teamIds.size} teams"))
        }

        // Snake-seed teams into groups
        val groupedTeams = snakeSeedTeamsWithSizes(teamIds, groupSizes)

        // Build AssignGroupsRequest
        val groups = groupedTeams.mapIndexed { index, teams ->
            GroupAssignment(groupNumber = index + 1, teamIds = teams)
        }

        val actualConfig = config.copy(
            groupCount = groupSizes.size,
            teamsPerGroup = groupSizes.maxOrNull() ?: 0
        )

        val request = AssignGroupsRequest(groups = groups, config = actualConfig)
        return generateGroupStage(tournamentId, categoryId, request)
    }

    /**
     * Helper class for building group standings
     */
    private data class GroupStandingBuilder(
        val teamId: String,
        val groupNumber: Int,
        var matchesPlayed: Int = 0,
        var matchesWon: Int = 0,
        var matchesLost: Int = 0,
        var gamesWon: Int = 0,
        var gamesLost: Int = 0,
        var totalPoints: Int = 0,
        // Head-to-head: opponentTeamId → (wins, losses, gamesFor, gamesAgainst)
        val headToHead: MutableMap<String, H2HRecord> = mutableMapOf()
    )

    private data class H2HRecord(
        var wins: Int = 0,
        var losses: Int = 0,
        var gamesFor: Int = 0,
        var gamesAgainst: Int = 0
    )

    /**
     * Generate round-robin matches for a group.
     * n teams = n*(n-1)/2 matches
     */
    private fun generateRoundRobinMatches(
        teamIds: List<String>,
        groupNumber: Int,
        startMatchNumber: Int
    ): List<GeneratedMatch> {
        val matches = mutableListOf<GeneratedMatch>()
        var matchNumber = startMatchNumber
        val n = teamIds.size

        // Generate all pairings using round-robin algorithm
        // Each team plays every other team exactly once
        for (i in 0 until n) {
            for (j in (i + 1) until n) {
                matches.add(GeneratedMatch(
                    roundNumber = 1,  // All group matches in round 1
                    matchNumber = matchNumber++,
                    roundName = "Grupo ${('A' + groupNumber - 1)}",
                    team1Id = teamIds[i],
                    team2Id = teamIds[j],
                    isBye = false,
                    status = "pending",
                    nextMatchNumber = null,
                    nextMatchPosition = null,
                    groupNumber = groupNumber
                ))
            }
        }

        return matches
    }

    // ============ Bracket Generation Algorithm ============

    /**
     * Process bye matches after bracket generation:
     * marks them as completed and advances the bye winner to the next round.
     */
    private suspend fun processByeMatches(
        createdMatches: List<MatchResponse>,
        matchNumberToId: Map<Int, String>
    ) {
        val byeMatches = createdMatches.filter { it.isBye && it.status == "bye" }

        for (byeMatch in byeMatches) {
            // Determine the winner (the non-null team)
            val winnerTeamId = byeMatch.team1Id ?: byeMatch.team2Id ?: continue
            val winnerTeam = if (byeMatch.team1Id != null) 1 else 2

            // Mark bye match as completed with the bye recipient as winner
            repository.updateMatchScore(
                matchId = byeMatch.id,
                scoreTeam1 = 0,
                scoreTeam2 = 0,
                setScores = emptyList(),
                winnerTeam = winnerTeam
            )

            // Advance winner to next match (next_match_id is set by this point)
            // Re-fetch the match to get the updated next_match_id
            val updatedMatch = repository.getMatch(byeMatch.id) ?: continue
            if (updatedMatch.nextMatchId != null) {
                repository.advanceWinner(byeMatch.id, winnerTeamId)
            }
        }
    }

    /**
     * Generate knockout matches from seeded teams.
     * Implements proper seeding placement so #1 meets #2 only in final.
     */
    private fun generateKnockoutMatches(teams: List<TeamSeed>): List<GeneratedMatch> {
        val teamCount = teams.size
        val bracketSize = nextPowerOfTwo(teamCount)
        val totalRounds = kotlin.math.log2(bracketSize.toDouble()).toInt()

        // Generate seed positions for bracket
        val seedPositions = generateSeedPositions(bracketSize)

        // Map teams to positions (with BYEs for missing seeds)
        val positionToTeam = mutableMapOf<Int, String?>()
        teams.forEach { team ->
            val position = seedPositions.indexOf(team.seed)
            if (position >= 0) {
                positionToTeam[position] = team.teamId
            }
        }
        // Fill remaining positions with null (BYEs)
        for (i in 0 until bracketSize) {
            if (!positionToTeam.containsKey(i)) {
                positionToTeam[i] = null
            }
        }

        val matches = mutableListOf<GeneratedMatch>()
        var matchNumber = 1

        // Track which match each position feeds into for next round linking
        val positionToMatchNumber = mutableMapOf<Int, Int>()

        // Generate round 1 matches
        val round1MatchCount = bracketSize / 2
        for (i in 0 until round1MatchCount) {
            val pos1 = i * 2
            val pos2 = i * 2 + 1
            val team1 = positionToTeam[pos1]
            val team2 = positionToTeam[pos2]

            val isBye = team1 == null || team2 == null
            val status = when {
                isBye -> "bye"
                else -> "pending"
            }

            val roundName = getRoundName(1, totalRounds)

            matches.add(
                GeneratedMatch(
                    roundNumber = 1,
                    matchNumber = matchNumber,
                    roundName = roundName,
                    team1Id = team1,
                    team2Id = team2,
                    isBye = isBye,
                    status = status,
                    nextMatchNumber = null,  // Will be set after all matches created
                    nextMatchPosition = null
                )
            )

            // Track which next-round position this match feeds
            positionToMatchNumber[i] = matchNumber
            matchNumber++
        }

        // Generate subsequent rounds
        var currentRoundMatches = round1MatchCount
        for (round in 2..totalRounds) {
            val matchesInRound = currentRoundMatches / 2
            val prevRoundFirstMatch = matchNumber - currentRoundMatches

            for (i in 0 until matchesInRound) {
                val feedMatch1 = prevRoundFirstMatch + (i * 2)
                val feedMatch2 = prevRoundFirstMatch + (i * 2) + 1

                matches.add(
                    GeneratedMatch(
                        roundNumber = round,
                        matchNumber = matchNumber,
                        roundName = getRoundName(round, totalRounds),
                        team1Id = null,  // To be filled by match results
                        team2Id = null,
                        isBye = false,
                        status = "pending",
                        nextMatchNumber = null,
                        nextMatchPosition = null
                    )
                )

                // Update feeding matches with next_match reference
                val feedIndex1 = matches.indexOfFirst { it.matchNumber == feedMatch1 }
                val feedIndex2 = matches.indexOfFirst { it.matchNumber == feedMatch2 }

                if (feedIndex1 >= 0) {
                    matches[feedIndex1] = matches[feedIndex1].copy(
                        nextMatchNumber = matchNumber,
                        nextMatchPosition = 1
                    )
                }
                if (feedIndex2 >= 0) {
                    matches[feedIndex2] = matches[feedIndex2].copy(
                        nextMatchNumber = matchNumber,
                        nextMatchPosition = 2
                    )
                }

                matchNumber++
            }

            currentRoundMatches = matchesInRound
        }

        return matches
    }

    /**
     * Calculate next power of 2 for bracket size
     */
    private fun nextPowerOfTwo(n: Int): Int {
        var power = 1
        while (power < n) power *= 2
        return power
    }

    /**
     * Generate seed positions for bracket.
     * This ensures top seeds meet only in later rounds.
     * For size 8: [1, 8, 5, 4, 3, 6, 7, 2]
     * So #1 is at position 0, #2 at position 7 (opposite ends)
     */
    private fun generateSeedPositions(bracketSize: Int): List<Int> {
        if (bracketSize == 2) return listOf(1, 2)

        val halfSize = bracketSize / 2
        val topHalf = generateSeedPositions(halfSize)
        val bottomHalf = topHalf.map { bracketSize + 1 - it }

        // Interleave: top and bottom pairs
        return topHalf.zip(bottomHalf).flatMap { (a, b) -> listOf(a, b) }
    }

    /**
     * Get round name based on round number and total rounds
     */
    private fun getRoundName(round: Int, totalRounds: Int): String {
        val roundsFromEnd = totalRounds - round + 1
        return when (roundsFromEnd) {
            1 -> "Final"
            2 -> "Semifinales"
            3 -> "Cuartos de final"
            4 -> "Octavos de final"
            5 -> "Dieciseisavos"
            else -> "Ronda $round"
        }
    }

    /**
     * Place teams in the knockout bracket ensuring teams from the same group
     * meet as late as possible (anti-rematch).
     *
     * Algorithm:
     * 1. Map bracket positions into hierarchical sections (halves, quarters, eighths)
     * 2. Group teams by their origin group
     * 3. For each group with N classified teams, calculate required separation:
     *    - N=2 → opposite halves (meet earliest at Final)
     *    - N=3-4 → different quarters (meet earliest at Semis)
     * 4. Place teams greedy: most-constrained groups first, respecting ranking
     * 5. Fall back to first-round conflict resolution if perfect placement fails
     */
    private fun placeTeamsAntiRematch(
        teams: List<TeamSeed>,
        teamGroupMap: Map<String, Int>
    ): List<TeamSeed> {
        if (teams.size <= 1) return teams

        val bracketSize = nextPowerOfTwo(teams.size)
        val seedPositions = generateSeedPositions(bracketSize)

        // Map each seed position to its bracket slot index (0-based)
        // seedPositions[slotIndex] = seedNumber
        // We need: for each seed, which slot it occupies
        val seedToSlot = mutableMapOf<Int, Int>()
        seedPositions.forEachIndexed { slot, seed -> seedToSlot[seed] = slot }

        // Build section assignments: for each slot, which half/quarter/eighth it belongs to
        // Half: slot / (bracketSize/2)  → 0 or 1
        // Quarter: slot / (bracketSize/4) → 0..3
        // Eighth: slot / (bracketSize/8) → 0..7
        fun slotHalf(slot: Int): Int = slot / (bracketSize / 2)
        fun slotQuarter(slot: Int): Int = if (bracketSize >= 4) slot / (bracketSize / 4) else slot / 2

        // Group teams by origin group
        val teamsByGroup = mutableMapOf<Int, MutableList<TeamSeed>>()
        for (team in teams) {
            val group = teamGroupMap[team.teamId] ?: continue
            teamsByGroup.getOrPut(group) { mutableListOf() }.add(team)
        }

        // Build mutable seed-to-team mapping
        val seedToTeam = mutableMapOf<Int, TeamSeed>()
        teams.forEach { seedToTeam[it.seed] = it }

        // Sort groups by size descending (most constrained first)
        val sortedGroups = teamsByGroup.entries.sortedByDescending { it.value.size }

        // Track which sections are taken by which groups
        val halfGroupCounts = mutableMapOf<Int, MutableMap<Int, Int>>() // half -> (group -> count)
        val quarterGroupCounts = mutableMapOf<Int, MutableMap<Int, Int>>() // quarter -> (group -> count)

        // Initialize counts from current placement
        for (team in teams) {
            val group = teamGroupMap[team.teamId] ?: continue
            val slot = seedToSlot[team.seed] ?: continue
            val half = slotHalf(slot)
            val quarter = slotQuarter(slot)
            halfGroupCounts.getOrPut(half) { mutableMapOf() }
                .merge(group, 1) { a, b -> a + b }
            quarterGroupCounts.getOrPut(quarter) { mutableMapOf() }
                .merge(group, 1) { a, b -> a + b }
        }

        // Try to resolve conflicts by swapping teams between positions
        // For each group with multiple teams, try to separate them
        for ((group, groupTeams) in sortedGroups) {
            if (groupTeams.size < 2) continue

            val teamsInGroup = groupTeams.sortedBy { it.seed } // respect ranking order

            if (teamsInGroup.size == 2) {
                // Need to be in opposite halves
                val t1 = teamsInGroup[0]
                val t2 = teamsInGroup[1]
                val slot1 = seedToSlot[t1.seed] ?: continue
                val slot2 = seedToSlot[t2.seed] ?: continue

                if (slotHalf(slot1) == slotHalf(slot2)) {
                    // Find a team in the opposite half to swap with t2
                    val targetHalf = 1 - slotHalf(slot1)
                    val swapCandidate = findSwapCandidate(
                        t2, group, targetHalf,
                        seedToTeam, seedToSlot, teamGroupMap,
                        ::slotHalf, ::slotQuarter, halfGroupCounts
                    )
                    if (swapCandidate != null) {
                        performSwap(t2, swapCandidate, seedToTeam, seedToSlot,
                            seedPositions, halfGroupCounts, quarterGroupCounts,
                            teamGroupMap, ::slotHalf, ::slotQuarter)
                    }
                }
            } else {
                // 3+ teams: try to spread across quarters
                for (i in 1 until teamsInGroup.size) {
                    val t = teamsInGroup[i]
                    val tSlot = seedToSlot[t.seed] ?: continue
                    val tQuarter = slotQuarter(tSlot)

                    // Check if any earlier team from same group is in same quarter
                    val conflict = (0 until i).any { j ->
                        val otherSlot = seedToSlot[teamsInGroup[j].seed] ?: -1
                        slotQuarter(otherSlot) == tQuarter
                    }

                    if (conflict) {
                        // Find a quarter with no team from this group
                        val usedQuarters = (0 until i).mapNotNull { j ->
                            seedToSlot[teamsInGroup[j].seed]?.let { slotQuarter(it) }
                        }.toSet()
                        val numQuarters = if (bracketSize >= 4) 4 else 2
                        val freeQuarter = (0 until numQuarters).firstOrNull { it !in usedQuarters }

                        if (freeQuarter != null) {
                            // Find a team in that quarter to swap with
                            val candidate = seedToTeam.values
                                .filter { candidate ->
                                    val cSlot = seedToSlot[candidate.seed] ?: -1
                                    val cGroup = teamGroupMap[candidate.teamId]
                                    slotQuarter(cSlot) == freeQuarter &&
                                    cGroup != group &&
                                    // Don't create a new conflict for the candidate's group
                                    !wouldCreateConflict(candidate, tSlot, teamGroupMap, seedToTeam,
                                        seedToSlot, ::slotHalf, ::slotQuarter)
                                }
                                .minByOrNull { it.seed } // prefer swapping lower-ranked teams

                            if (candidate != null) {
                                performSwap(t, candidate, seedToTeam, seedToSlot,
                                    seedPositions, halfGroupCounts, quarterGroupCounts,
                                    teamGroupMap, ::slotHalf, ::slotQuarter)
                            }
                        }
                    }
                }
            }
        }

        // Final safety pass: resolve any remaining first-round conflicts
        resolveFirstRoundConflicts(seedToTeam, seedPositions, bracketSize, teamGroupMap, seedToSlot)

        return seedToTeam.values.sortedBy { it.seed }
    }

    /**
     * Find a swap candidate in the target half that won't create new conflicts.
     */
    private fun findSwapCandidate(
        teamToMove: TeamSeed,
        teamGroup: Int,
        targetHalf: Int,
        seedToTeam: Map<Int, TeamSeed>,
        seedToSlot: Map<Int, Int>,
        teamGroupMap: Map<String, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int,
        halfGroupCounts: Map<Int, Map<Int, Int>>
    ): TeamSeed? {
        return seedToTeam.values
            .filter { candidate ->
                val cSlot = seedToSlot[candidate.seed] ?: return@filter false
                val cGroup = teamGroupMap[candidate.teamId]
                slotHalf(cSlot) == targetHalf &&
                cGroup != teamGroup &&
                candidate.seed != teamToMove.seed
            }
            .minByOrNull { Math.abs(it.seed - teamToMove.seed) } // prefer similar ranking
    }

    /**
     * Check if placing a candidate at a target slot would create a same-group
     * conflict with another team already in that half/quarter.
     */
    private fun wouldCreateConflict(
        candidate: TeamSeed,
        targetSlot: Int,
        teamGroupMap: Map<String, Int>,
        seedToTeam: Map<Int, TeamSeed>,
        seedToSlot: Map<Int, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int
    ): Boolean {
        val candidateGroup = teamGroupMap[candidate.teamId] ?: return false
        val targetQuarter = slotQuarter(targetSlot)

        // Check if any other team from candidate's group is in the target quarter
        return seedToTeam.values.any { other ->
            if (other.seed == candidate.seed) return@any false
            val otherGroup = teamGroupMap[other.teamId]
            val otherSlot = seedToSlot[other.seed] ?: return@any false
            otherGroup == candidateGroup && slotQuarter(otherSlot) == targetQuarter
        }
    }

    /**
     * Swap two teams' seeds and update tracking maps.
     */
    private fun performSwap(
        team1: TeamSeed,
        team2: TeamSeed,
        seedToTeam: MutableMap<Int, TeamSeed>,
        seedToSlot: MutableMap<Int, Int>,
        seedPositions: List<Int>,
        halfGroupCounts: MutableMap<Int, MutableMap<Int, Int>>,
        quarterGroupCounts: MutableMap<Int, MutableMap<Int, Int>>,
        teamGroupMap: Map<String, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int
    ) {
        val seed1 = team1.seed
        val seed2 = team2.seed

        val group1 = teamGroupMap[team1.teamId] ?: return
        val group2 = teamGroupMap[team2.teamId] ?: return

        // Remove old counts
        val oldSlot1 = seedToSlot[seed1] ?: return
        val oldSlot2 = seedToSlot[seed2] ?: return

        halfGroupCounts[slotHalf(oldSlot1)]?.merge(group1, -1) { a, b -> a + b }
        halfGroupCounts[slotHalf(oldSlot2)]?.merge(group2, -1) { a, b -> a + b }
        quarterGroupCounts[slotQuarter(oldSlot1)]?.merge(group1, -1) { a, b -> a + b }
        quarterGroupCounts[slotQuarter(oldSlot2)]?.merge(group2, -1) { a, b -> a + b }

        // Swap
        val newTeam1 = TeamSeed(team2.teamId, seed1)
        val newTeam2 = TeamSeed(team1.teamId, seed2)
        seedToTeam[seed1] = newTeam1
        seedToTeam[seed2] = newTeam2

        // Add new counts
        halfGroupCounts.getOrPut(slotHalf(oldSlot1)) { mutableMapOf() }
            .merge(group2, 1) { a, b -> a + b }
        halfGroupCounts.getOrPut(slotHalf(oldSlot2)) { mutableMapOf() }
            .merge(group1, 1) { a, b -> a + b }
        quarterGroupCounts.getOrPut(slotQuarter(oldSlot1)) { mutableMapOf() }
            .merge(group2, 1) { a, b -> a + b }
        quarterGroupCounts.getOrPut(slotQuarter(oldSlot2)) { mutableMapOf() }
            .merge(group1, 1) { a, b -> a + b }
    }

    /**
     * Final safety pass: resolve any remaining first-round same-group conflicts.
     * This catches edge cases where the hierarchical placement couldn't fully resolve.
     */
    private fun resolveFirstRoundConflicts(
        seedToTeam: MutableMap<Int, TeamSeed>,
        seedPositions: List<Int>,
        bracketSize: Int,
        teamGroupMap: Map<String, Int>,
        seedToSlot: MutableMap<Int, Int>
    ) {
        val matchPairs = (0 until bracketSize step 2).map { i ->
            seedPositions[i] to seedPositions[i + 1]
        }

        for (i in matchPairs.indices) {
            val (s1, s2) = matchPairs[i]
            val t1 = seedToTeam[s1] ?: continue
            val t2 = seedToTeam[s2] ?: continue
            val g1 = teamGroupMap[t1.teamId]
            val g2 = teamGroupMap[t2.teamId]

            if (g1 != null && g1 == g2) {
                for (j in matchPairs.indices) {
                    if (j == i) continue
                    val (os1, os2) = matchPairs[j]

                    val ot2 = seedToTeam[os2]
                    if (ot2 != null) {
                        val og2 = teamGroupMap[ot2.teamId]
                        val og1 = teamGroupMap[seedToTeam[os1]?.teamId ?: ""]
                        if (og2 != g1 && (og1 == null || og1 != g2)) {
                            val newT2 = TeamSeed(ot2.teamId, s2)
                            val newOt2 = TeamSeed(t2.teamId, os2)
                            seedToTeam[s2] = newT2
                            seedToTeam[os2] = newOt2
                            break
                        }
                    }

                    val ot1 = seedToTeam[os1]
                    if (ot1 != null) {
                        val og1 = teamGroupMap[ot1.teamId]
                        val og2j = teamGroupMap[seedToTeam[os2]?.teamId ?: ""]
                        if (og1 != g1 && (og2j == null || og2j != g2)) {
                            val newT2 = TeamSeed(ot1.teamId, s2)
                            val newOt1 = TeamSeed(t2.teamId, os1)
                            seedToTeam[s2] = newT2
                            seedToTeam[os1] = newOt1
                            break
                        }
                    }
                }
            }
        }
    }

    // ============ Status and Withdrawal ============

    /**
     * Update match status without changing score.
     * Useful for starting/pausing matches or marking as in_progress.
     */
    suspend fun updateMatchStatus(matchId: String, status: String): Result<MatchResponse> {
        return try {
            val updated = repository.updateMatchStatus(matchId, status)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update match schedule (court number and scheduled time).
     * Used by the scheduling UI to assign matches to courts/times.
     * Pass null values to clear the schedule.
     */
    suspend fun updateMatchSchedule(matchId: String, courtNumber: Int?, scheduledTime: String?): Result<MatchResponse> {
        return try {
            val updated = repository.updateMatchSchedule(matchId, courtNumber, scheduledTime)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Withdraw a team from the tournament.
     * Marks all their pending/scheduled matches as forfeit and advances opponents.
     */
    suspend fun withdrawTeam(
        tournamentId: String,
        categoryId: Int,
        teamId: String,
        reason: String?,
        organizerId: String? = null
    ): Result<WithdrawTeamResponse> {
        return try {
            // Get bracket for this category
            val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
                ?: return Result.failure(IllegalArgumentException("Bracket not found"))

            val bracket = bracketWithMatches.bracket

            // Get all matches for this team
            val matches = repository.getMatchesForTeam(bracket.id, teamId)
            val pendingMatches = matches.filter { it.status in listOf("pending", "scheduled") }

            val forfeitedIds = mutableListOf<String>()

            for (match in pendingMatches) {
                // Determine winner (opponent gets the win)
                val winnerTeam = if (match.team1Id == teamId) 2 else 1

                // Mark as forfeit with winner
                repository.updateMatchForfeit(match.id, winnerTeam)
                forfeitedIds.add(match.id)

                // Advance winner to next match if exists
                if (match.nextMatchId != null) {
                    val winnerId = if (winnerTeam == 1) match.team1Id else match.team2Id
                    if (winnerId != null) {
                        repository.advanceToNextMatch(
                            match.id,
                            winnerId,
                            match.nextMatchId,
                            match.nextMatchPosition ?: 1
                        )
                    }
                }
            }

            // Recalculate group standings if this is a groups_knockout bracket
            if (bracket.format == "groups_knockout" && forfeitedIds.isNotEmpty()) {
                calculateGroupStandings(tournamentId, categoryId)
            }

            auditLog.log("bracket", bracket.id, "withdraw", organizerId, mapOf(
                "team_id" to teamId,
                "reason" to (reason ?: ""),
                "forfeited_matches" to forfeitedIds.size.toString()
            ))

            Result.success(WithdrawTeamResponse(
                forfeitedMatches = forfeitedIds,
                message = "Team withdrawn. ${forfeitedIds.size} match(es) forfeited."
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
