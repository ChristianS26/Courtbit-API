package models.registrationcode

import kotlinx.serialization.Serializable

@Serializable
data class RegistrationCode(
    val id: String,
    val code: String,
    val used: Boolean,
    val created_by_email: String,
    val used_by_email: String? = null,
    val created_at: String? = null,
    val used_at: String? = null,
    val used_in_tournament_id: String? = null,
    val organizer_id: String? = null,
)
