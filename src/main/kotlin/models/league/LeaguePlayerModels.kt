package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LeaguePlayerResponse(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_uid") val userUid: String?,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("is_waiting_list") val isWaitingList: Boolean = false,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("discount_reason") val discountReason: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("has_paid") val hasPaid: Boolean = false
)

@Serializable
data class CreateLeaguePlayerRequest(
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_uid") val userUid: String? = null,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("discount_reason") val discountReason: String? = null
)

@Serializable
data class UpdateLeaguePlayerRequest(
    val name: String? = null,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("discount_amount") val discountAmount: Long? = null,
    @SerialName("discount_reason") val discountReason: String? = null
)

/**
 * Request for player self-registration (player registers themselves)
 * Unlike CreateLeaguePlayerRequest, this doesn't have discount fields
 * and the userUid is extracted from the JWT token
 */
@Serializable
data class SelfRegisterRequest(
    @SerialName("category_id") val categoryId: String,
    val name: String,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null
)

/**
 * Error response for self-registration failures
 */
@Serializable
data class SelfRegisterError(
    val error: String,
    val code: String
)
