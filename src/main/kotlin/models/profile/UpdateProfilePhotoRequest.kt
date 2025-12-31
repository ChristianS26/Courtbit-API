package models.profile

import kotlinx.serialization.Serializable
import io.ktor.server.request.receive

@Serializable
data class UpdateProfilePhotoRequest(val photo_url: String)