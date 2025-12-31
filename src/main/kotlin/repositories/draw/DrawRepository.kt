package repositories.draw

import models.draw.DrawRequest
import models.draw.DrawResponse


interface DrawRepository {
    suspend fun getDrawsByTournament(tournamentId: String): List<DrawResponse>
    suspend fun createDraw(draw: DrawRequest): Boolean?
    suspend fun deleteDraw(drawId: String): Boolean
}
