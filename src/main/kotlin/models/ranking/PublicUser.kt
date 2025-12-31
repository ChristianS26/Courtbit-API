package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicUser(
    val uid: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    val phone: String? = null,
)
