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
     * Get bracket by ID
     */
    suspend fun getBracketById(bracketId: String): BracketResponse?

    /**
     * Get bracket with all its matches
     */
    suspend fun getBracketWithMatches(tournamentId: String, categoryId: Int): BracketWithMatchesResponse?

    /**
     * Get all brackets for a tournament
     */
    suspend fun getBracketsByTournament(tournamentId: String): List<BracketResponse>

    /**
     * Get all brackets with matches, standings, and players for a tournament (bulk fetch)
     */
    suspend fun getAllBracketsWithMatches(tournamentId: String): List<BracketWithMatchesResponse>

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
     * Get all matches for a bracket by bracket ID
     */
    suspend fun getMatchesByBracketId(bracketId: String): List<MatchResponse>

    /**
     * Delete a bracket and all its matches (cascade)
     */
    suspend fun deleteBracket(bracketId: String): Boolean

    /**
     * Delete all matches for a bracket (used when regenerating)
     */
    suspend fun deleteMatchesByBracketId(bracketId: String): Boolean

    /**
     * Delete specific matches by their IDs (used for deleting knockout phase only)
     * @return Number of matches deleted
     */
    suspend fun deleteMatchesByIds(matchIds: List<String>): Int

    // ============ Match Scoring ============

    /**
     * Get a single match by ID
     */
    suspend fun getMatch(matchId: String): MatchResponse?

    /**
     * Update match score, set scores, winner, and status.
     * If expectedVersion is provided, uses optimistic locking (returns failure if version mismatch).
     */
    suspend fun updateMatchScore(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        expectedVersion: Int? = null
    ): Result<MatchResponse>

    /**
     * Atomically update match score AND advance winner to next match in a single transaction.
     * Uses a Supabase RPC function to prevent race conditions between score update and advancement.
     * Returns the updated match and whether the winner was advanced.
     */
    suspend fun updateMatchScoreAndAdvance(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        expectedVersion: Int? = null,
        submittedByUserId: String? = null
    ): Result<Pair<MatchResponse, Boolean>>

    /**
     * Reset match score: clear scores, set_scores, winner_team, and set status back to pending.
     */
    suspend fun resetMatchScore(matchId: String): Result<MatchResponse>

    /**
     * Atomically reset match score AND reverse advancement to next match.
     * Locks both the current and next match to prevent TOCTOU race conditions.
     */
    suspend fun resetMatchScoreAtomic(matchId: String): Result<MatchResponse>

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

    // ============ Groups + Knockout ============

    /**
     * Update bracket config JSON
     */
    suspend fun updateBracketConfig(bracketId: String, configJson: String): Boolean

    /**
     * Update match teams and group number (for swapping teams between groups)
     */
    suspend fun updateMatchTeams(matchId: String, team1Id: String?, team2Id: String?, groupNumber: Int?): Boolean

    /**
     * Update standing's group number (for swapping teams between groups)
     */
    suspend fun updateStandingGroupNumber(bracketId: String, teamId: String, groupNumber: Int): Boolean

    /**
     * Atomically swap two teams between groups: updates all match references and standings.
     * Uses a Supabase RPC to ensure all-or-nothing swap.
     */
    suspend fun swapTeamsInGroupsAtomic(bracketId: String, team1Id: String, team2Id: String): Result<Unit>

    /**
     * Update match schedule (court number and scheduled time)
     * Pass null values to clear the schedule
     */
    suspend fun updateMatchSchedule(matchId: String, courtNumber: Int?, scheduledTime: String?): MatchResponse

    // ============ Player Score Submission ============

    /**
     * Get player UIDs for a team (player_a_uid, player_b_uid)
     */
    suspend fun getTeamPlayerUids(teamId: String): List<String>

    /**
     * Check if a tournament allows player score submission
     */
    suspend fun getTournamentAllowPlayerScores(tournamentId: String): Boolean

    /**
     * Update match score with audit trail (submitted_by_user_id, submitted_at)
     */
    suspend fun updateMatchScoreWithAudit(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        submittedByUserId: String,
        expectedVersion: Int? = null
    ): Result<MatchResponse>
}
