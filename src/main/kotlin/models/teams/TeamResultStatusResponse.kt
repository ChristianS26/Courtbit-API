package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class TeamResultStatusResponse(
    val teamId: String,
    val hasResult: Boolean,
    val teamResultId: String? = null,
    val position: String? = null,
    val pointsAwarded: Int? = null,
    val season: String? = null,
    val resultUpdatedAt: String? = null
)
