package models.category

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CategoryResponseDto(
    val id: Int,
    val name: String,
    @SerialName("category_type") val categoryType: String? = null,
    val gender: String? = null,
    val level: Int? = null,
    val position: Int? = null
)
