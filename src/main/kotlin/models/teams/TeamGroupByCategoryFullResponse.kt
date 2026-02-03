package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class TeamGroupByCategoryFullResponse(
    val categoryName: String,
    val teams: List<TeamWithPlayerDto>,
    val maxTeams: Int? = null  // Maximum teams allowed for this category (null = unlimited)
)
