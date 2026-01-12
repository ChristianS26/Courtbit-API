package repositories.league

import models.league.CreateLeaguePaymentRequest
import models.league.LeaguePaymentResponse
import models.league.PlayerPaymentSummary
import models.league.SeasonPaymentReportRow
import models.league.UpdateLeaguePaymentRequest

interface LeaguePaymentRepository {
    suspend fun getById(id: String): LeaguePaymentResponse?
    suspend fun getByPlayerId(playerId: String): List<LeaguePaymentResponse>
    suspend fun getBySeasonId(seasonId: String): List<LeaguePaymentResponse>
    suspend fun create(request: CreateLeaguePaymentRequest, registeredByUid: String): LeaguePaymentResponse?
    suspend fun update(id: String, request: UpdateLeaguePaymentRequest): Boolean
    suspend fun delete(id: String): Boolean

    // RPC functions
    suspend fun getPlayerPaymentSummary(playerId: String): PlayerPaymentSummary?
    suspend fun getSeasonPaymentReport(seasonId: String): List<SeasonPaymentReportRow>
}
