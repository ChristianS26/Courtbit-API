package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class TeamGroupByCategoryFullResponse(
    val categoryName: String,
    val teams: List<TeamWithPlayerDto>,
    val maxTeams: Int? = null,  // Maximum teams allowed for this category (null = unlimited)
    val color: String? = null   // Hex color configured by organizer (e.g. "#3B82F6")
)
