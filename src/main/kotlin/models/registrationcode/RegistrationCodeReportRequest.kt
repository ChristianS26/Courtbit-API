package models.registrationcode

import kotlinx.serialization.Serializable

@Serializable
data class RegistrationCodeReportRequest(
    val email: String
)
