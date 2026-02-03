package models.category

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TournamentCategoryRequest(
    val tournamentId: String,
    val categoryIds: List<Int>? = null,  // Simple list (backward compatible)
    val categories: List<CategoryWithColor>? = null  // With colors and limits
)

@Serializable
data class CategoryWithColor(
    @SerialName("category_id") val categoryId: Int,
    val color: String? = null,   // Hex color like "#3B82F6"
    val maxTeams: Int? = null    // Maximum teams allowed
)
