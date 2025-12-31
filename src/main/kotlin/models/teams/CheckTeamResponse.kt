package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class CheckTeamResponse(
    val exists: Boolean,
    val teamId: String? = null
)
