package services.category

import models.category.CategoryPriceResponse
import models.category.CategoryResponseDto
import models.category.TournamentCategoryDto
import models.category.TournamentCategoryRequest
import repositories.category.CategoryRepository

class CategoryService(
    private val repository: CategoryRepository
) {
    suspend fun getAllCategories(): List<CategoryResponseDto> = repository.getAll()

    suspend fun assignCategoriesToTournament(request: TournamentCategoryRequest): Boolean {
        return repository.assignCategoriesToTournament(request)
    }
    suspend fun getCategoriesForTournament(tournamentId: String): List<TournamentCategoryDto> {
        return repository.getCategoriesByTournamentId(tournamentId)
    }

    suspend fun getCategoryPricesForTournament(tournamentId: String, tournamentType: String): List<CategoryPriceResponse> {
        return repository.getCategoryPricesForTournament(tournamentId, tournamentType)
    }

    suspend fun getCategoryIdsByTournament(tournamentId: String): List<String> {
        return getCategoriesForTournament(tournamentId).map { it.id }
    }

    suspend fun getCategoryNamesByIds(ids: List<Int>): List<String> {
        return repository.getCategoriesByIds(ids).map { it.name }
    }

    suspend fun updateCategoryMaxTeams(tournamentId: String, categoryId: Int, maxTeams: Int?): Boolean {
        return repository.updateCategoryMaxTeams(tournamentId, categoryId, maxTeams)
    }

    suspend fun getNaturalCategories(gender: String? = null): List<CategoryResponseDto> {
        return repository.getNaturalCategories(gender)
    }
}
