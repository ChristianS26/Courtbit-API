package models.auth

@kotlinx.serialization.Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
