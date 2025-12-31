package models.category

import kotlinx.serialization.Serializable

@Serializable
data class TournamentCategoryDto(
    val id: String,
    val name: String,
    val position: Int
)
