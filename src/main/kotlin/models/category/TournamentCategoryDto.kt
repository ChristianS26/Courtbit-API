package models.category

import kotlinx.serialization.Serializable

@Serializable
data class TournamentCategoryDto(
    val id: String,
    val name: String,
    val color: String? = null,  // Hex color like "#3B82F6"
    val maxTeams: Int? = null   // Maximum teams allowed for this category
)
