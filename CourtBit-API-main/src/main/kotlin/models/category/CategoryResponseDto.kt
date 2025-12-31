package models.category

import kotlinx.serialization.Serializable

@Serializable
data class CategoryResponseDto(
    val id: Int,
    val name: String,
    val position: Int
)
