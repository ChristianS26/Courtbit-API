package com.incodap.models.users

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val uid: String,
    val email: String,
    @SerialName("password_hash") val passwordHash: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val phone: String? = null,
    val gender: String? = null,
    val birthdate: String? = null,
    val role: String,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("country_iso") val countryIso: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = false,
    @SerialName("shirt_size") val shirtSize: String? = null,
)
