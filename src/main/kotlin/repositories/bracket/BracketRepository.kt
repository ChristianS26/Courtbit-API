package repositories.bracket

import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.GeneratedMatch
import models.bracket.MatchResponse
import models.bracket.SetScore
import models.bracket.StandingEntry
import models.bracket.StandingInput

/**
 * Repository interface for bracket operations
 */
interface BracketRepository {
    /**
     * Get bracket by tournament and category
     */
    suspend fun getBracket(tournamentId: String, categoryId: Int): BracketResponse?

    /**
     * Get bracket with all its matches
     */
    suspend fun getBracketWithMatches(tournamentId: String, categoryId: Int): BracketWithMatchesResponse?

    /**
     * Create a new bracket
     */
    suspend fun createBracket(
        tournamentId: String,
        categoryId: Int,
        format: String,
        seedingMethod: String
    ): BracketResponse?

    /**
     * Create matches for a bracket.
     * Returns the created matches with their UUIDs populated.
     */
    suspend fun createMatches(bracketId: String, matches: List<GeneratedMatch>): List<MatchResponse>

    /**
     * Update match with next_match_id reference
     */
    suspend fun updateMatchNextMatchId(matchId: String, nextMatchId: String, position: Int): Boolean

    /**
     * Update bracket status (draft -> published)
     */
    suspend fun updateBracketStatus(bracketId: String, status: String): Boolean

    /**
     * Delete a bracket and all its matches (cascade)
     */
    suspend fun deleteBracket(bracketId: String): Boolean

    // ============ Match Scoring ============

    /**
     * Get a single match by ID
     */
    suspend fun getMatch(matchId: String): MatchResponse?

    /**
     * Update match score, set scores, winner, and status
     */
    suspend fun updateMatchScore(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int
    ): Result<MatchResponse>

    /**
     * Advance winner to next match.
     * Updates the next match's team1_id or team2_id based on next_match_position.
     */
    suspend fun advanceWinner(matchId: String, winnerTeamId: String): Result<Unit>

    // ============ Standings ============

    /**
     * Get standings for a bracket
     */
    suspend fun getStandings(bracketId: String): List<StandingEntry>

    /**
     * Upsert standings (insert or update)
     */
    suspend fun upsertStandings(standings: List<StandingInput>): Boolean

    /**
     * Delete all standings for a bracket
     */
    suspend fun deleteStandings(bracketId: String): Boolean

    // ============ Status and Withdrawal ============

    /**
     * Update match status without changing score
     */
    suspend fun updateMatchStatus(matchId: String, status: String): MatchResponse

    /**
     * Get all matches for a specific team in a bracket
     */
    suspend fun getMatchesForTeam(bracketId: String, teamId: String): List<MatchResponse>

    /**
     * Mark a match as forfeit with the specified winner
     */
    suspend fun updateMatchForfeit(matchId: String, winnerTeam: Int): Boolean

    /**
     * Advance winner to next match by updating team1_id or team2_id
     */
    suspend fun advanceToNextMatch(matchId: String, winnerId: String, nextMatchId: String, position: Int): Boolean
}
