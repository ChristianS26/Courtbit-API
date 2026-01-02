package repositories.league

import models.league.DoublesMatchResponse
import models.league.UpdateMatchScoreRequest

interface DoublesMatchRepository {
    suspend fun getById(id: String): DoublesMatchResponse?
    suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest): Boolean
}
