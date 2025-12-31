package models.registrationcode

import kotlinx.serialization.Serializable

@Serializable
data class RegistrationCodeResponse(
    val code: String,
    val tournamentId: String,
    val category: String
)
