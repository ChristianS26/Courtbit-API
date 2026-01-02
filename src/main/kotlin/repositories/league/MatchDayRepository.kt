package repositories.league

import models.league.MatchDayResponse

interface MatchDayRepository {
    suspend fun getAll(): List<MatchDayResponse>
    suspend fun getByCategoryId(categoryId: String): List<MatchDayResponse>
    suspend fun getById(id: String): MatchDayResponse?
}
