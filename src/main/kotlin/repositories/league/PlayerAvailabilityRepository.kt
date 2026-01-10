package repositories.league

import models.league.*

interface PlayerAvailabilityRepository {
    // Matchday availability
    suspend fun getByPlayerId(playerId: String, seasonId: String): List<PlayerAvailabilityResponse>
    suspend fun getBySeasonId(seasonId: String): List<PlayerAvailabilityResponse>
    suspend fun getBySeasonAndMatchday(seasonId: String, matchdayNumber: Int): List<PlayerAvailabilityResponse>
    suspend fun create(request: CreatePlayerAvailabilityRequest): PlayerAvailabilityResponse?
    suspend fun update(id: String, request: UpdatePlayerAvailabilityRequest): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun upsertBatch(request: BatchPlayerAvailabilityRequest): Boolean

    // Combined queries
    suspend fun getPlayerAvailabilitySummary(playerId: String, seasonId: String): PlayerAvailabilitySummary?
    suspend fun getAvailabilityForSlot(
        categoryId: String,
        seasonId: String,
        matchdayNumber: Int,
        timeSlot: String
    ): SlotAvailabilityResponse
}
