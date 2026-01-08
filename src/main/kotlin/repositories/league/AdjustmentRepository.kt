package repositories.league

import models.league.CreateAdjustmentRequest
import models.league.LeagueAdjustmentResponse
import models.league.UpdateAdjustmentRequest

interface AdjustmentRepository {
    suspend fun getByCategory(categoryId: String): List<LeagueAdjustmentResponse>
    suspend fun getById(id: String): LeagueAdjustmentResponse?
    suspend fun create(request: CreateAdjustmentRequest, createdByUid: String): LeagueAdjustmentResponse?
    suspend fun update(id: String, request: UpdateAdjustmentRequest): Boolean
    suspend fun delete(id: String): Boolean
}
