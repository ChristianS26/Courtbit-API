package com.incodap.models.payments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class PaymentRecord(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("stripe_payment_id")
    val stripePaymentId: String,
    val amount: Long,
    @SerialName("customer_id")
    val customerId: String? = null,
    val email: String? = null,
    @SerialName("player_name")
    val playerName: String? = null,
    @SerialName("tournament_id")
    val tournamentId: String? = null,
    @SerialName("paid_at")
    val paidAt: String = Instant.now().toString()
)
