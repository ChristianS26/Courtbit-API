package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class PaymentsReportRequest(
    val tournamentId: String,
    val tournamentName: String,
    val email: String? = null
)