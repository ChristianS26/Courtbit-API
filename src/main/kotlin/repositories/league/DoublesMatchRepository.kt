package repositories.league

import models.league.DoublesMatchResponse
import models.league.UpdateMatchScoreRequest

interface DoublesMatchRepository {
    suspend fun getById(id: String): DoublesMatchResponse?
    suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest, submittedByName: String? = null): Boolean
    suspend fun markForfeit(
        matchId: String,
        forfeitedPlayerIds: List<String>,
        recordedByUid: String
    ): Boolean
    suspend fun reverseForfeit(matchId: String, clearScores: Boolean): Boolean

    /**
     * Get the season's max_points_per_game setting for a given match.
     * Traverses: match → rotation → day_group → category → season
     * Returns 6 as default if not found.
     */
    suspend fun getSeasonMaxPointsForMatch(matchId: String): Int
}
