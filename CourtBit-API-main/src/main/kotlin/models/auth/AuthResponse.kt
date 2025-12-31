package models.auth

import kotlinx.serialization.Serializable
import models.users.PublicUser

@Serializable
data class AuthResponse(
    val token: String,
    val user: PublicUser
)
