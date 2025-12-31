package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class ReportRequest(
    val tournamentId: String,
    val tournamentName: String,
    val email: String
)
