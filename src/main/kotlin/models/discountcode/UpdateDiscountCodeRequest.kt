package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateDiscountCodeRequest(
    @SerialName("is_active") val isActive: Boolean? = null,
    val description: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("max_uses") val maxUses: Int? = null,
)
