package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiscountCodeUsageResponse(
    val id: String,
    @SerialName("discount_code_id") val discountCodeId: String,
    val code: String,
    @SerialName("discount_type") val discountType: String,
    @SerialName("discount_value") val discountValue: Int,
    @SerialName("organization_id") val organizationId: String,
    @SerialName("tournament_id") val tournamentId: String,
    @SerialName("tournament_name") val tournamentName: String,
    @SerialName("player_uid") val playerUid: String,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("player_email") val playerEmail: String? = null,
    @SerialName("original_amount") val originalAmount: Int? = null,
    @SerialName("discount_amount") val discountAmount: Int? = null,
    @SerialName("final_amount") val finalAmount: Int? = null,
    @SerialName("used_at") val usedAt: String? = null,
)
