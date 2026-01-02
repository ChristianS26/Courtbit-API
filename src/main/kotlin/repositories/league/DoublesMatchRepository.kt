package repositories.league

import models.league.UpdateMatchScoreRequest

interface DoublesMatchRepository {
    suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest): Boolean
}
