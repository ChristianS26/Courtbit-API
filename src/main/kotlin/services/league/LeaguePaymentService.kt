package services.league

import models.league.*
import repositories.league.LeagueCategoryRepository
import repositories.league.LeaguePaymentRepository
import repositories.league.LeaguePlayerRepository

class LeaguePaymentService(
    private val paymentRepository: LeaguePaymentRepository,
    private val playerRepository: LeaguePlayerRepository,
    private val categoryRepository: LeagueCategoryRepository
) {
    /**
     * Gets the seasonId for a player by looking up their category.
     * Returns null if player or category not found.
     */
    suspend fun getSeasonIdForPlayer(playerId: String): String? {
        val player = playerRepository.getById(playerId) ?: return null
        val category = categoryRepository.getById(player.categoryId) ?: return null
        return category.seasonId
    }
    suspend fun getPaymentById(id: String): LeaguePaymentResponse? {
        return paymentRepository.getById(id)
    }

    suspend fun getPaymentsByPlayer(playerId: String): List<LeaguePaymentResponse> {
        return paymentRepository.getByPlayerId(playerId)
    }

    suspend fun getPaymentsBySeason(seasonId: String): List<LeaguePaymentResponse> {
        return paymentRepository.getBySeasonId(seasonId)
    }

    suspend fun createPayment(
        request: CreateLeaguePaymentRequest,
        registeredByUid: String
    ): Result<LeaguePaymentResponse> {
        // Validate player exists
        val player = playerRepository.getById(request.leaguePlayerId)
            ?: return Result.failure(IllegalArgumentException("Player not found"))

        // Validate amount is positive
        if (request.amount <= 0) {
            return Result.failure(IllegalArgumentException("Amount must be positive"))
        }

        // Create the payment
        val payment = paymentRepository.create(request, registeredByUid)
            ?: return Result.failure(Exception("Failed to create payment"))

        return Result.success(payment)
    }

    suspend fun updatePayment(id: String, request: UpdateLeaguePaymentRequest): Boolean {
        return paymentRepository.update(id, request)
    }

    suspend fun deletePayment(id: String): Boolean {
        return paymentRepository.delete(id)
    }

    suspend fun getPlayerPaymentSummary(playerId: String): PlayerPaymentSummary? {
        return paymentRepository.getPlayerPaymentSummary(playerId)
    }

    suspend fun getSeasonPaymentReport(seasonId: String): List<SeasonPaymentReportRow> {
        return paymentRepository.getSeasonPaymentReport(seasonId)
    }

    /**
     * Get a summary of payment statistics for a season
     */
    suspend fun getSeasonPaymentStats(seasonId: String): SeasonPaymentStats {
        val report = paymentRepository.getSeasonPaymentReport(seasonId)

        val totalPlayers = report.size
        val paidPlayers = report.count { it.isFullyPaid }
        val partiallyPaidPlayers = report.count { !it.isFullyPaid && it.totalPaid > 0 }
        val unpaidPlayers = report.count { it.totalPaid == 0L }

        val totalExpected = report.sumOf { it.effectiveFee }
        val totalCollected = report.sumOf { it.totalPaid }
        val totalPending = report.sumOf { it.balanceDue }

        return SeasonPaymentStats(
            totalPlayers = totalPlayers,
            paidPlayers = paidPlayers,
            partiallyPaidPlayers = partiallyPaidPlayers,
            unpaidPlayers = unpaidPlayers,
            totalExpected = totalExpected,
            totalCollected = totalCollected,
            totalPending = totalPending,
            collectionPercentage = if (totalExpected > 0) {
                (totalCollected.toDouble() / totalExpected.toDouble() * 100).toInt()
            } else 0
        )
    }
}

@kotlinx.serialization.Serializable
data class SeasonPaymentStats(
    val totalPlayers: Int,
    val paidPlayers: Int,
    val partiallyPaidPlayers: Int,
    val unpaidPlayers: Int,
    val totalExpected: Long,
    val totalCollected: Long,
    val totalPending: Long,
    val collectionPercentage: Int
)
