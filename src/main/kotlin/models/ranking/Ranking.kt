package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class Ranking(
    @SerialName("id")
    val id: String? = null,

    @SerialName("user_id")
    val userId: String,

    @SerialName("category")
    val category: CategoryResponseDto,

    @SerialName("season")
    val season: String,

    @SerialName("total_points")
    val totalPoints: Int = 0,

    @SerialName("updated_at")
    val updatedAt: String? = null,
)
