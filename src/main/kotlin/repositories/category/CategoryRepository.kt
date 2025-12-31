package repositories.category

import models.category.CategoryPriceResponse
import models.category.CategoryResponseDto
import models.category.TournamentCategoryDto
import models.category.TournamentCategoryRequest

interface CategoryRepository {
    suspend fun getAll(): List<CategoryResponseDto>
    suspend fun assignCategoriesToTournament(request: TournamentCategoryRequest): Boolean
    suspend fun getCategoriesByTournamentId(tournamentId: String): List<TournamentCategoryDto>
    suspend fun getCategoryPricesForTournament(tournamentId: String, tournamentType: String): List<CategoryPriceResponse>
    suspend fun getCategoriesByIds(ids: List<Int>): List<CategoryResponseDto>
}
