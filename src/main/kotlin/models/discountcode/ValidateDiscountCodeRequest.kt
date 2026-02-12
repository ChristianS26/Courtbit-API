package models.discountcode

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ValidateDiscountCodeRequest(
    val code: String,
    @SerialName("tournament_id") val tournamentId: String,
)
