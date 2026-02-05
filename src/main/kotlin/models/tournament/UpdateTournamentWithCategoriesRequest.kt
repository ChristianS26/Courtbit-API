package models.tournament

import com.incodap.models.tournament.UpdateTournamentRequest
import kotlinx.serialization.Serializable

@Serializable
data class UpdateTournamentWithCategoriesRequest(
    val tournament: UpdateTournamentRequest,
    val categoryIds: List<Int>,
    val categoryPrices: List<CategoryPriceRequest>? = null,
    val categoryColors: List<CategoryColorRequest>? = null
)
