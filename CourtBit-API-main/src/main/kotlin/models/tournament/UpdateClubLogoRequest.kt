package models.tournament

import kotlinx.serialization.Serializable

@Serializable
data class UpdateClubLogoRequest(
    val club_logo_url: String
)
