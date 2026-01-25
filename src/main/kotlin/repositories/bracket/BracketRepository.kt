package repositories.bracket

import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.GeneratedMatch
import models.bracket.MatchResponse

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
}
