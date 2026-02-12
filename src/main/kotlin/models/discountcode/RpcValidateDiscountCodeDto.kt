package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RpcValidateDiscountCodeDto(
    @SerialName("p_code") val code: String,
    @SerialName("p_tournament_id") val tournamentId: String,
    @SerialName("p_player_uid") val playerUid: String,
)
