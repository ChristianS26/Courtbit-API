package repositories.league

import models.league.CreateSeasonRequest
import models.league.SeasonResponse
import models.league.UpdateSeasonRequest

interface SeasonRepository {
    suspend fun getAll(): List<SeasonResponse>
    suspend fun getByOrganizerId(organizerId: String): List<SeasonResponse>
    suspend fun getById(id: String): SeasonResponse?
    suspend fun create(request: CreateSeasonRequest): SeasonResponse?
    suspend fun update(id: String, request: UpdateSeasonRequest): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun getActiveByOrganizer(organizerId: String): SeasonResponse?
}
