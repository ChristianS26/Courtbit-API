package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class CreateIntentResponse(
    val paymentIntentClientSecret: String?,
    val customerId: String? = null,
    val ephemeralKey: String? = null
)