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

// Season-specific shirt size configuration
@Serializable
data class SeasonShirtSizeConfig(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("shirt_size_id") val shirtSizeId: String,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class SeasonShirtSizeRequest(
    @SerialName("shirt_size_ids") val shirtSizeIds: List<String>
)

@Serializable
data class SeasonShirtSizesResponse(
    @SerialName("season_id") val seasonId: String,
    @SerialName("available_styles") val availableStyles: List<String>,
    val sizes: ShirtSizeCatalogResponse
)
