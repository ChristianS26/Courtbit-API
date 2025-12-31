package models.tournament

import kotlinx.serialization.Serializable

@Serializable
data class CreateTournamentWithCategoriesRequest(
    val tournament: CreateTournamentRequest,
    val categoryIds: List<Int>
)
