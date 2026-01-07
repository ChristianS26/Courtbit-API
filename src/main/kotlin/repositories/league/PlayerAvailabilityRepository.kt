package repositories.league

import models.league.*

interface PlayerAvailabilityRepository {
    // Default weekly availability
    suspend fun getByPlayerId(playerId: String, seasonId: String): List<PlayerAvailabilityResponse>
    suspend fun getBySeasonId(seasonId: String): List<PlayerAvailabilityResponse>
    suspend fun create(request: CreatePlayerAvailabilityRequest): PlayerAvailabilityResponse?
    suspend fun update(id: String, request: UpdatePlayerAvailabilityRequest): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun upsertBatch(request: BatchPlayerAvailabilityRequest): Boolean

    // Overrides for specific dates
    suspend fun getOverridesByPlayerId(playerId: String, seasonId: String): List<PlayerAvailabilityOverrideResponse>
    suspend fun getOverridesBySeasonAndDate(seasonId: String, date: String): List<PlayerAvailabilityOverrideResponse>
    suspend fun createOverride(request: CreatePlayerAvailabilityOverrideRequest): PlayerAvailabilityOverrideResponse?
    suspend fun updateOverride(id: String, request: UpdatePlayerAvailabilityOverrideRequest): Boolean
    suspend fun deleteOverride(id: String): Boolean

    // Combined queries
    suspend fun getPlayerAvailabilitySummary(playerId: String, seasonId: String): PlayerAvailabilitySummary?
    suspend fun getAvailabilityForSlot(
        categoryId: String,
        seasonId: String,
        date: String,
        timeSlot: String
    ): SlotAvailabilityResponse
}
