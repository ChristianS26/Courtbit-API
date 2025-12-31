package models.auth

@kotlinx.serialization.Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)
