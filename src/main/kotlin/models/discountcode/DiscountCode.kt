package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiscountCode(
    val id: String,
    @SerialName("organization_id") val organizationId: String,
    val code: String,
    val description: String? = null,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Int,
    @SerialName("usage_type") val usageType: String = "single_use",
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("times_used") val timesUsed: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    @SerialName("created_by_email") val createdByEmail: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
