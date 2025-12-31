package models.tournament

import kotlinx.serialization.Serializable

@Serializable
data class UpdateFlyerRequest(val flyer_url: String)
