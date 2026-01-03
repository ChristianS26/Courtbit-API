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
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateLeaguePlayerRequest(
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_uid") val userUid: String? = null,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?
)

@Serializable
data class UpdateLeaguePlayerRequest(
    val name: String? = null,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null
)
