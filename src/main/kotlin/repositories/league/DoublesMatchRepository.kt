package repositories.league

import models.league.DoublesMatchResponse
import models.league.UpdateMatchScoreRequest

interface DoublesMatchRepository {
    suspend fun getById(id: String): DoublesMatchResponse?
    suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest, submittedByName: String? = null): Boolean
    suspend fun markForfeit(
        matchId: String,
        forfeitedPlayerIds: List<String>,
        scoreTeam1: Int,
        scoreTeam2: Int,
        recordedByUid: String
    ): Boolean
    suspend fun reverseForfeit(matchId: String, clearScores: Boolean): Boolean
}
