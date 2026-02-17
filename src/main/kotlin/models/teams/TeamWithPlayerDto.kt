package models.teams// models/teams/TeamWithFullPlayerInfo.kt
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class TeamWithPlayerDto(
    val id: String,
    val category: CategoryResponseDto,
    val playerA: TeamPlayerDto,
    val playerB: TeamPlayerDto,
    val playerAPoints: Int,
    val playerAPaid: Boolean,
    val playerBPoints: Int,
    val playerBPaid: Boolean,
    val hasResult: Boolean = false,
    val restriction: String? = null,
    val scheduleRestriction: ScheduleRestriction? = null
)
