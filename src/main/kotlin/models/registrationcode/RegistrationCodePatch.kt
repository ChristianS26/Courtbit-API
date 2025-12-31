package models.registrationcode

@kotlinx.serialization.Serializable
data class RegistrationCodePatch(
    val used: Boolean = true,
    val used_by_email: String? = null,
    val used_at: String? = null,
    val used_in_tournament_id: String? = null,
)
