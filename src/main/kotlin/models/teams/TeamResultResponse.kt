package models.ranking

import kotlinx.serialization.Serializable

@Serializable
data class TeamResultResponse(
    val teamResultId: String,
    val message: String = "Result set"
)