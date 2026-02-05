package services.draw

import models.draw.DrawRequest
import models.draw.DrawResponse
import repositories.draw.DrawRepository

class DrawService(private val drawRepository: DrawRepository) {

    suspend fun getDrawsByTournament(tournamentId: String): List<DrawResponse> {
        return drawRepository.getDrawsByTournament(tournamentId)
            .sortedWith(
                compareBy<DrawResponse> { it.category.name.lowercase() }
            )
    }

    suspend fun createDraw(draw: DrawRequest): Boolean? =
        drawRepository.createDraw(draw)

    suspend fun deleteDraw(drawId: String): Boolean =
        drawRepository.deleteDraw(drawId)
}
