package repositories.league

import models.league.PlayerMatchdayAvailabilityResponse

interface PlayerMatchdayAvailabilityRepository {
    suspend fun getBySeasonId(seasonId: String): List<PlayerMatchdayAvailabilityResponse>
    suspend fun getBySeasonAndMatchday(seasonId: String, matchdayNumber: Int): List<PlayerMatchdayAvailabilityResponse>
    suspend fun getByPlayerId(playerId: String): List<PlayerMatchdayAvailabilityResponse>
}
