package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class RankingItemResponse(
    val category: CategoryResponseDto,
    @SerialName("total_points") val totalPoints: Int,
    val position: Int? = null,
    val user: PublicUser
)
