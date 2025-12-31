package models.category

import kotlinx.serialization.Serializable

@Serializable
data class CategoryPosition(
    val id: String,
    val position: Int
)