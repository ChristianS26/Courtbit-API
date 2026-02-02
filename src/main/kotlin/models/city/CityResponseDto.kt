package models.city

import kotlinx.serialization.Serializable

@Serializable
data class CityResponseDto(
    val id: Int,
    val name: String,
    val state: String? = null
)
