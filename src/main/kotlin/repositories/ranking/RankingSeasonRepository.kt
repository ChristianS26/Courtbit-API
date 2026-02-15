package repositories.ranking

import models.ranking.RankingSeasonResponse

interface RankingSeasonRepository {
    suspend fun getAllByOrganizer(organizerId: String): List<RankingSeasonResponse>
    suspend fun getActiveByOrganizer(organizerId: String): RankingSeasonResponse?
    suspend fun getById(id: String): RankingSeasonResponse?
    suspend fun create(body: String): RankingSeasonResponse
    suspend fun update(id: String, body: String): RankingSeasonResponse?
    suspend fun delete(id: String): Boolean
}
