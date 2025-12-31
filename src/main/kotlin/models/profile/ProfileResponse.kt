package models.profile

import kotlinx.serialization.Serializable
import models.users.PublicUser

@Serializable
data class ProfileResponse(
    val user: PublicUser,
    val accessToken: String
)
