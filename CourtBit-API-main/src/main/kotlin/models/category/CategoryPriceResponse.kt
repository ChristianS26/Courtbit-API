package models.category

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class CategoryPriceResponse(
    val id: String? = null,

    @SerialName("tournament_type")
    val tournamentType: String? = null,

    @SerialName("category_id")
    val categoryId: Int,

    val price: Int,

    @SerialName("category_name")
    val categoryName: String
)
