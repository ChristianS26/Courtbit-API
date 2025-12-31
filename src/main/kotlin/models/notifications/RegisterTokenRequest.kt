package models.notifications

import kotlinx.serialization.Serializable

@Serializable
data class RegisterTokenRequest(
    val token: String,
    val platform: String = "android", // "android"|"ios" por si expandes
    val deviceId: String? = null,
    val appFlavor: String,            // "stage" | "prod"
)

@Serializable
data class RegisterTokenResponse(val ok: Boolean)

@Serializable
data class DeleteTokenRequest(val token: String)
