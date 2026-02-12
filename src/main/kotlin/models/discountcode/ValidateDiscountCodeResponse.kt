package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ValidateDiscountCodeResponse(
    val valid: Boolean,
    val error: String? = null,
    val message: String? = null,
    @SerialName("discount_code_id") val discountCodeId: String? = null,
    @SerialName("discount_type") val discountType: String? = null,
    @SerialName("discount_value") val discountValue: Int? = null,
    @SerialName("original_amount") val originalAmount: Int? = null,
    @SerialName("discount_amount") val discountAmount: Int? = null,
    @SerialName("final_amount") val finalAmount: Int? = null,
    val applied: Boolean? = null,
    @SerialName("free_registration") val freeRegistration: Boolean? = null,
    @SerialName("team_id") val teamId: String? = null,
)
