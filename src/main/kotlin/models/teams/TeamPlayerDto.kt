package models.teams

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TeamPlayerDto(
    @SerialName("uid")
    val uid: String? = null,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String,
    @SerialName("photo_url")
    val photoUrl: String? = null,
    @SerialName("phone")
    val phone: String? = null,
    @SerialName("gender")
    val gender: String? = null,
    @SerialName("email")
    val email: String? = null,
    @SerialName("is_manual")
    val isManual: Boolean = false
)

