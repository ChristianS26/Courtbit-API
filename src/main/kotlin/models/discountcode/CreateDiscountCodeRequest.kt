package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateDiscountCodeRequest(
    val code: String,
    val description: String? = null,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Int,
    @SerialName("usage_type") val usageType: String = "single_use",
    @SerialName("max_uses") val maxUses: Int? = null,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (code.isBlank()) errors.add("Code is required.")
        if (discountType !in listOf("percentage", "fixed_amount")) errors.add("discount_type must be 'percentage' or 'fixed_amount'.")
        if (discountValue <= 0) errors.add("discount_value must be greater than 0.")
        if (discountType == "percentage" && discountValue > 100) errors.add("Percentage discount cannot exceed 100.")
        if (usageType !in listOf("single_use", "unlimited")) errors.add("usage_type must be 'single_use' or 'unlimited'.")
        if (maxUses != null && maxUses <= 0) errors.add("max_uses must be greater than 0.")
        return errors
    }
}
