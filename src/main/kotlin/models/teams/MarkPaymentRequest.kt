package models.teams

import kotlinx.serialization.Serializable

@Serializable
data class MarkPaymentRequest(
    val teamId: String,
    val tournamentId: String,
    val paidBy: String,
    val paid: Boolean,
    val method: String? = null,
    val playerUid: String
)
