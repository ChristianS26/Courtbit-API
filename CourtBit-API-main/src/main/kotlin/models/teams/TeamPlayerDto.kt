package models.teams

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class TeamPlayerDto(
    @SerialName("uid")
    val uid: String,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String,
    @SerialName("photo_url")
    val photoUrl: String?,
    @SerialName("phone")
    val phone: String?,
    @SerialName("gender")
    val gender: String?
)

