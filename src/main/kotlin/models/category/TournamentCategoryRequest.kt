package models.category

import kotlinx.serialization.Serializable

@Serializable
data class TournamentCategoryRequest(
    val tournamentId: String,
    val categoryIds: List<Int>
)
