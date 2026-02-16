package services.bracket

import kotlinx.serialization.json.Json
import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.MatchResponse
import models.bracket.WithdrawTeamResponse
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository

/**
 * Service for bracket lifecycle, retrieval, match management, and withdrawal.
 */
class BracketService(
    private val repository: BracketRepository,
    private val json: Json,
    private val auditLog: BracketAuditRepository
) {
    // ============ Retrieval ============

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

    // ============ Lifecycle ============

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

    // ============ Match Management ============

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

    // ============ Withdrawal ============

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
