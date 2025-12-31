package models.users

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicUser(
    @SerialName("uid")
    val uid: String,

    @SerialName("email")
    val email: String,

    @SerialName("first_name")
    val firstName: String,

    @SerialName("last_name")
    val lastName: String,

    @SerialName("phone")
    val phone: String? = null,

    @SerialName("country_iso")
    val countryIso: String? = null,

    @SerialName("gender")
    val gender: String? = null,

    @SerialName("role")
    val role: String,

    @SerialName("photo_url")
    val photoUrl: String? = null,

    @SerialName("email_verified")
    val emailVerified: Boolean? = false,

    @SerialName("shirt_size") val shirtSize: String? = null,
    )
