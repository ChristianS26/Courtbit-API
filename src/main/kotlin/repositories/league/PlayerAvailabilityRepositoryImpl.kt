package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.league.*
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PlayerAvailabilityRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val leaguePlayerRepository: LeaguePlayerRepository
) : PlayerAvailabilityRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(PlayerAvailabilityRepositoryImpl::class.java)

    // ==================== Default Weekly Availability ====================

    override suspend fun getByPlayerId(playerId: String, seasonId: String): List<PlayerAvailabilityResponse> {
        val response = client.get("$apiUrl/player_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("player_id", "eq.$playerId")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "day_of_week.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityResponse>>(bodyText)
        } else {
            logger.error("Failed to get availability by player: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getBySeasonId(seasonId: String): List<PlayerAvailabilityResponse> {
        val response = client.get("$apiUrl/player_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "player_id.asc,day_of_week.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityResponse>>(bodyText)
        } else {
            logger.error("Failed to get availability by season: ${response.status}")
            emptyList()
        }
    }

    override suspend fun create(request: CreatePlayerAvailabilityRequest): PlayerAvailabilityResponse? {
        val payload = buildJsonObject {
            put("player_id", request.playerId)
            put("season_id", request.seasonId)
            put("day_of_week", request.dayOfWeek)
            put("available_time_slots", JsonArray(request.availableTimeSlots.map { JsonPrimitive(it) }))
        }

        val response = client.post("$apiUrl/player_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(payload))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityResponse>>(bodyText).firstOrNull()
        } else {
            logger.error("Failed to create availability: ${response.status}")
            null
        }
    }

    override suspend fun update(id: String, request: UpdatePlayerAvailabilityRequest): Boolean {
        val payload = buildJsonObject {
            put("available_time_slots", JsonArray(request.availableTimeSlots.map { JsonPrimitive(it) }))
            put("updated_at", JsonPrimitive("now()"))
        }

        val response = client.patch("$apiUrl/player_availability?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val response = client.delete("$apiUrl/player_availability?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
        }

        return response.status.isSuccess()
    }

    override suspend fun upsertBatch(request: BatchPlayerAvailabilityRequest): Boolean {
        // First delete all existing availability for this player+season
        val deleteResponse = client.delete("$apiUrl/player_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            parameter("player_id", "eq.${request.playerId}")
            parameter("season_id", "eq.${request.seasonId}")
        }

        if (!deleteResponse.status.isSuccess()) {
            logger.error("Failed to delete existing availability: ${deleteResponse.status}")
            return false
        }

        // Then insert all new availability records
        if (request.availabilities.isEmpty()) {
            return true
        }

        val payload = request.availabilities.map { dayAvailability ->
            buildJsonObject {
                put("player_id", request.playerId)
                put("season_id", request.seasonId)
                put("day_of_week", dayAvailability.dayOfWeek)
                put("available_time_slots", JsonArray(dayAvailability.availableTimeSlots.map { JsonPrimitive(it) }))
            }
        }

        val response = client.post("$apiUrl/player_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        return response.status.isSuccess()
    }

    // ==================== Overrides ====================

    override suspend fun getOverridesByPlayerId(
        playerId: String,
        seasonId: String
    ): List<PlayerAvailabilityOverrideResponse> {
        val response = client.get("$apiUrl/player_availability_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("player_id", "eq.$playerId")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "override_date.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityOverrideResponse>>(bodyText)
        } else {
            logger.error("Failed to get overrides by player: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getOverridesBySeasonAndDate(
        seasonId: String,
        date: String
    ): List<PlayerAvailabilityOverrideResponse> {
        val response = client.get("$apiUrl/player_availability_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("override_date", "eq.$date")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityOverrideResponse>>(bodyText)
        } else {
            logger.error("Failed to get overrides by date: ${response.status}")
            emptyList()
        }
    }

    override suspend fun createOverride(request: CreatePlayerAvailabilityOverrideRequest): PlayerAvailabilityOverrideResponse? {
        val payload = buildJsonObject {
            put("player_id", request.playerId)
            put("season_id", request.seasonId)
            put("override_date", request.overrideDate)
            put("available_time_slots", JsonArray(request.availableTimeSlots.map { JsonPrimitive(it) }))
            put("is_unavailable", request.isUnavailable)
            request.reason?.let { put("reason", it) }
        }

        val response = client.post("$apiUrl/player_availability_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(payload))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityOverrideResponse>>(bodyText).firstOrNull()
        } else {
            logger.error("Failed to create override: ${response.status}")
            null
        }
    }

    override suspend fun updateOverride(id: String, request: UpdatePlayerAvailabilityOverrideRequest): Boolean {
        val payload = buildJsonObject {
            request.availableTimeSlots?.let {
                put("available_time_slots", JsonArray(it.map { slot -> JsonPrimitive(slot) }))
            }
            request.isUnavailable?.let { put("is_unavailable", it) }
            request.reason?.let { put("reason", it) }
            put("updated_at", JsonPrimitive("now()"))
        }

        val response = client.patch("$apiUrl/player_availability_overrides?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        return response.status.isSuccess()
    }

    override suspend fun deleteOverride(id: String): Boolean {
        val response = client.delete("$apiUrl/player_availability_overrides?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
        }

        return response.status.isSuccess()
    }

    // ==================== Combined Queries ====================

    override suspend fun getPlayerAvailabilitySummary(
        playerId: String,
        seasonId: String
    ): PlayerAvailabilitySummary? {
        // Get player info
        val player = leaguePlayerRepository.getById(playerId) ?: return null

        // Get weekly availability
        val weeklyAvailability = getByPlayerId(playerId, seasonId)
        val weeklyMap = weeklyAvailability.associate { it.dayOfWeek to it.availableTimeSlots }

        // Get overrides
        val overrides = getOverridesByPlayerId(playerId, seasonId)

        return PlayerAvailabilitySummary(
            playerId = playerId,
            playerName = player.name,
            weeklyAvailability = weeklyMap,
            overrides = overrides
        )
    }

    override suspend fun getAvailabilityForSlot(
        categoryId: String,
        seasonId: String,
        date: String,
        timeSlot: String
    ): SlotAvailabilityResponse {
        // Get all players in the category
        val players = leaguePlayerRepository.getByCategoryId(categoryId)
            .filter { !it.isWaitingList }

        // Get all weekly availability for this season
        val weeklyAvailability = getBySeasonId(seasonId)
        val weeklyByPlayer = weeklyAvailability.groupBy { it.playerId }

        // Get all overrides for this specific date
        val overrides = getOverridesBySeasonAndDate(seasonId, date)
        val overridesByPlayer = overrides.associateBy { it.playerId }

        // Determine day of week from date
        val localDate = LocalDate.parse(date)
        val dayOfWeek = localDate.dayOfWeek.value % 7 // Convert to 0-6 (Sunday = 0)

        val availablePlayers = mutableListOf<AvailablePlayer>()
        val unavailablePlayers = mutableListOf<UnavailablePlayer>()

        for (player in players) {
            val override = overridesByPlayer[player.id]
            val weekly = weeklyByPlayer[player.id]?.find { it.dayOfWeek == dayOfWeek }

            val (isAvailable, reason) = when {
                // Check override first
                override != null -> {
                    if (override.isUnavailable) {
                        false to override.reason
                    } else {
                        (timeSlot in override.availableTimeSlots) to null
                    }
                }
                // Fall back to weekly availability
                weekly != null -> {
                    (timeSlot in weekly.availableTimeSlots) to null
                }
                // No availability data = unavailable
                else -> {
                    false to "No availability set"
                }
            }

            if (isAvailable) {
                availablePlayers.add(AvailablePlayer(player.id, player.name))
            } else {
                unavailablePlayers.add(UnavailablePlayer(player.id, player.name, reason))
            }
        }

        return SlotAvailabilityResponse(
            timeSlot = timeSlot,
            date = date,
            availablePlayers = availablePlayers,
            unavailablePlayers = unavailablePlayers
        )
    }
}
