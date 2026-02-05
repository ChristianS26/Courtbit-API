package services.ranking

import com.incodap.repositories.ranking.RankingRepository
import models.ranking.AddRankingEventRequest
import models.ranking.BatchRankingRequest
import models.ranking.PlayerProfileResponse
import models.ranking.PublicUser
import models.ranking.Ranking
import models.ranking.RankingItemResponse
import java.text.Collator
import java.util.Locale

class RankingService(
    private val repository: RankingRepository,
) {

    suspend fun addRankingEvent(request: AddRankingEventRequest): String {
        return repository.addRankingEvent(request)
    }

    suspend fun batchAddRankingEvents(request: BatchRankingRequest): List<String> {
        return request.entries.map { entry ->
            repository.addRankingEvent(
                AddRankingEventRequest(
                    userId = entry.userId,
                    season = request.season,
                    categoryId = request.categoryId,
                    points = entry.points,
                    tournamentId = request.tournamentId,
                    position = entry.position
                )
            )
        }
    }

    suspend fun getRanking(
        season: String?,
        categoryId: Int?,
        organizerId: String? = null
    ): List<RankingItemResponse> {
        // Collator en español para ordenar nombres ignorando mayúsculas/acentos
        val collator = Collator.getInstance(Locale("es", "ES")).apply {
            strength = Collator.PRIMARY
        }

        val sorted = repository.getRanking(season, categoryId, organizerId)
            .sortedWith(
                compareByDescending<RankingItemResponse> { it.totalPoints }
                    .thenComparator { a, b ->
                        val nameA = a.user.fullName()
                        val nameB = b.user.fullName()
                        collator.compare(nameA, nameB)
                    }
            )

        // Posiciones únicas (1..N)
        return sorted.mapIndexed { index, item ->
            item.copy(position = index + 1)
        }
    }

    // Helper para armar nombre (ajusta si prefieres "Apellido, Nombre")
    private fun PublicUser.fullName(): String =
        "${firstName.orEmpty()} ${lastName.orEmpty()}".trim()


    suspend fun getRankingByUser(userId: String, season: String?): List<Ranking> {
        return repository.getRankingByUser(userId, season)
    }

    suspend fun getPlayerProfile(userId: String, categoryId: Int): PlayerProfileResponse {
        return repository.getPlayerProfile(userId, categoryId)
    }

    suspend fun getRankingForMultipleUsersAndCategories(userIds: List<String>, categoryIds: List<Int>, season: String?): List<Ranking> {
        return repository.getRankingForMultipleUsersAndCategories(userIds, categoryIds, season)
    }
}
