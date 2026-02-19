package models.ranking

import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class PlayerProfileResponse(
    val user: PublicUser,
    val position: Int,
    val points: Int,
    val tournamentsWon: Int,
    val finalsReached: Int,
    val category: CategoryResponseDto,
    val history: List<PlayerTournamentHistoryItem>,
)

@Serializable
data class PlayerTournamentHistoryItem(
    val tournamentId: String,
    val tournamentName: String,
    val date: String?,
    val result: String, // "Campeón", "Finalista", "Participante"
    val pointsEarned: Int = 0,
    val categoryName: String? = null,
)
