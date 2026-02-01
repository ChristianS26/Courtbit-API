package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CategoryPriceRequest(
    @SerialName("category_id")
    val categoryId: Int,
    val price: Int // Price in cents
)

@Serializable
data class CategoryColorRequest(
    @SerialName("category_id")
    val categoryId: Int,
    val color: String? = null // Hex color like "#3B82F6"
)

@Serializable
data class CreateTournamentWithCategoriesRequest(
    val tournament: CreateTournamentRequest,
    val categoryIds: List<Int>,
    val categoryPrices: List<CategoryPriceRequest>? = null,
    val categoryColors: List<CategoryColorRequest>? = null
)
