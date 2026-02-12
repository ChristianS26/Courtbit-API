package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class CreateIntentResponse(
    val paymentIntentClientSecret: String?,
    val customerId: String? = null,
    val ephemeralKey: String? = null,
    val isFreeRegistration: Boolean = false,
    val originalAmount: Long? = null,
    val discountApplied: Long? = null,
    val finalAmount: Long? = null,
    val discountCode: String? = null,
)