package models.tournament

import kotlinx.serialization.Serializable

@Serializable
data class UpdateFlyerRequest(
    val flyer_url: String,
    val flyer_position: String? = null
)
