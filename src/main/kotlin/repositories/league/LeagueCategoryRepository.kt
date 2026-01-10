package repositories.league

import models.league.CategoryPlayoffConfigResponse
import models.league.CreateLeagueCategoryRequest
import models.league.LeagueCategoryResponse
import models.league.UpdateCategoryPlayoffConfigRequest
import models.league.UpdateLeagueCategoryRequest

interface LeagueCategoryRepository {
    suspend fun getAll(): List<LeagueCategoryResponse>
    suspend fun getBySeasonId(seasonId: String): List<LeagueCategoryResponse>
    suspend fun getById(id: String): LeagueCategoryResponse?
    suspend fun create(request: CreateLeagueCategoryRequest): LeagueCategoryResponse?
    suspend fun update(id: String, request: UpdateLeagueCategoryRequest): Boolean
    suspend fun delete(id: String): Boolean

    // Playoff configuration
    suspend fun getEffectivePlayoffConfig(categoryId: String): CategoryPlayoffConfigResponse?
    suspend fun updatePlayoffConfig(categoryId: String, request: UpdateCategoryPlayoffConfigRequest): Boolean
    suspend fun clearPlayoffConfig(categoryId: String): Boolean
}
