package services.ranking

import com.incodap.repositories.ranking.RankingRepository
import models.ranking.AddRankingEventRequest
import models.ranking.BatchRankingRequest
import models.ranking.BatchRankingRpcResponse
import models.ranking.PlayerProfileResponse
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

    suspend fun batchAddRankingEvents(request: BatchRankingRequest): BatchRankingRpcResponse {
        request.entries.forEachIndexed { index, entry ->
            val hasUser = !entry.userId.isNullOrBlank()
            val hasMember = !entry.teamMemberId.isNullOrBlank()
            if (!hasUser && !hasMember) {
                throw IllegalArgumentException("Entry $index must have either userId or teamMemberId")
            }
            if (hasUser && hasMember) {
                throw IllegalArgumentException("Entry $index must have only one of userId or teamMemberId, not both")
            }
        }

        val entries = request.entries.map { entry ->
            buildMap<String, Any?> {
                entry.userId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
                entry.teamMemberId?.takeIf { it.isNotBlank() }?.let { put("team_member_id", it) }
                put("points", entry.points)
                put("position", entry.position)
                entry.teamResultId?.let { put("team_result_id", it) }
                entry.playerName?.let { put("player_name", it) }
            }
        }

        val tournamentName = repository.getTournamentName(request.tournamentId)

        return repository.batchAddRankingEvents(
            tournamentId = request.tournamentId,
            categoryId = request.categoryId,
            season = request.season,
            entries = entries,
            tournamentName = tournamentName
        )
    }

    suspend fun checkExistingEvents(tournamentId: String, categoryId: Int): Boolean {
        return repository.checkExistingEvents(tournamentId, categoryId)
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
                        val nameA = a.displayName()
                        val nameB = b.displayName()
                        collator.compare(nameA, nameB)
                    }
            )

        // Posiciones únicas (1..N)
        return sorted.mapIndexed { index, item ->
            item.copy(position = index + 1)
        }
    }

    private fun RankingItemResponse.displayName(): String =
        user?.let { "${it.firstName.orEmpty()} ${it.lastName.orEmpty()}".trim() }
            ?: playerName
            ?: ""


    suspend fun getRankingByUser(userId: String, season: String?): List<Ranking> {
        return repository.getRankingByUser(userId, season)
    }

    suspend fun getPlayerProfile(userId: String, categoryId: Int, season: String? = null): PlayerProfileResponse {
        return repository.getPlayerProfile(userId, categoryId, season)
    }

    suspend fun getRankingForMultipleUsersAndCategories(userIds: List<String>, categoryIds: List<Int>, season: String?): List<Ranking> {
        return repository.getRankingForMultipleUsersAndCategories(userIds, categoryIds, season)
    }
}
