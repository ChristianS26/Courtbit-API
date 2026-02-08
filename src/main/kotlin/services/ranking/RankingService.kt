package services.ranking

import com.incodap.repositories.ranking.RankingRepository
import repositories.category.CategoryRepository
import models.ranking.AddRankingEventRequest
import models.ranking.AddTeamMemberRankingEventRequest
import models.ranking.BatchRankingRequest
import models.ranking.PlayerProfileResponse
import models.ranking.PublicUser
import models.ranking.Ranking
import models.ranking.RankingItemResponse
import java.text.Collator
import java.util.Locale

class RankingService(
    private val repository: RankingRepository,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun addRankingEvent(request: AddRankingEventRequest): String {
        return repository.addRankingEvent(request)
    }

    suspend fun batchAddRankingEvents(request: BatchRankingRequest): List<String> {
        // Only natural categories can receive ranking points
        val categories = categoryRepository.getCategoriesByIds(listOf(request.categoryId))
        val category = categories.firstOrNull()
            ?: throw IllegalArgumentException("Category ${request.categoryId} not found")
        if (category.categoryType != "natural") {
            throw IllegalArgumentException("Only natural categories can receive ranking points")
        }

        return request.entries.mapIndexed { index, entry ->
            val hasUser = entry.userId != null
            val hasTeamMember = entry.teamMemberId != null

            require(hasUser || hasTeamMember) {
                "Entry at index $index must have either userId or teamMemberId"
            }
            require(!(hasUser && hasTeamMember)) {
                "Entry at index $index cannot have both userId and teamMemberId"
            }

            if (hasUser) {
                repository.addRankingEvent(
                    AddRankingEventRequest(
                        userId = entry.userId!!,
                        season = request.season,
                        categoryId = request.categoryId,
                        points = entry.points,
                        tournamentId = request.tournamentId,
                        position = entry.position
                    )
                )
            } else {
                repository.addTeamMemberRankingEvent(
                    AddTeamMemberRankingEventRequest(
                        teamMemberId = entry.teamMemberId!!,
                        season = request.season,
                        categoryId = request.categoryId,
                        points = entry.points,
                        tournamentId = request.tournamentId,
                        position = entry.position
                    )
                )
            }
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
