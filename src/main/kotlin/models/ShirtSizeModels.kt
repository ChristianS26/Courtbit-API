package models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShirtSizeResponse(
    val id: String,
    @SerialName("size_code") val sizeCode: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("gender_style") val genderStyle: String,
    @SerialName("sort_order") val sortOrder: Int
)

@Serializable
data class ShirtSizeCatalogResponse(
    val unisex: List<ShirtSizeResponse>,
    val mens: List<ShirtSizeResponse>,
    val womens: List<ShirtSizeResponse>
)
