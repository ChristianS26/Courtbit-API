package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class TeamGroupByCategoryFullResponse(
    val categoryName: String,
    val teams: List<TeamWithPlayerDto>
)
