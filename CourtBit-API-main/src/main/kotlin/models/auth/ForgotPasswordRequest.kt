package models.auth

import kotlinx.serialization.Serializable

@Serializable
data class ForgotPasswordRequest(val email: String)
