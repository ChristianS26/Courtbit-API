package models.registrationcode

import kotlinx.serialization.Serializable

@Serializable
data class RegistrationCodeWithTournamentInfo(
    val id: String,
    val code: String,
    val used: Boolean,
    val created_by_email: String,
    val used_by_email: String? = null,
    val created_at: String? = null,
    val used_at: String? = null,
    val used_in_tournament_id: String? = null,
    val tournaments: TournamentNameWrapper? = null
) {
    val tournament_name: String?
        get() = tournaments?.name
}

@Serializable
data class TournamentNameWrapper(val name: String? = null)