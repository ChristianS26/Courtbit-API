package repositories.league

import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.SelfRegisterRequest
import models.league.UpdateLeaguePlayerRequest

interface LeaguePlayerRepository {
    suspend fun getAll(): List<LeaguePlayerResponse>
    suspend fun getByCategoryId(categoryId: String): List<LeaguePlayerResponse>
    suspend fun getById(id: String): LeaguePlayerResponse?
    suspend fun getByUserUidAndCategoryId(userUid: String, categoryId: String): LeaguePlayerResponse?
    suspend fun create(request: CreateLeaguePlayerRequest): LeaguePlayerResponse?
    suspend fun update(id: String, request: UpdateLeaguePlayerRequest): Boolean
    suspend fun delete(id: String): Boolean

    /**
     * Self-registration for players
     * Returns the created player or null if validation fails
     */
    suspend fun selfRegister(userUid: String, request: SelfRegisterRequest): Result<LeaguePlayerResponse>
}
