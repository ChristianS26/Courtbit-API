package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RpcApplyDiscountCodeDto(
    @SerialName("p_code") val code: String,
    @SerialName("p_tournament_id") val tournamentId: String,
    @SerialName("p_player_uid") val playerUid: String,
    @SerialName("p_partner_uid") val partnerUid: String,
    @SerialName("p_category_id") val categoryId: String,
    @SerialName("p_player_name") val playerName: String,
    @SerialName("p_restriction") val restriction: String? = null,
    @SerialName("p_used_by_email") val usedByEmail: String? = null,
    @SerialName("p_original_amount") val originalAmount: Int? = null,
)
