package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import models.league.*
import org.slf4j.LoggerFactory

class PlayerAvailabilityRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val leaguePlayerRepository: LeaguePlayerRepository
) : PlayerAvailabilityRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(PlayerAvailabilityRepositoryImpl::class.java)
    private val tableName = "player_matchday_availability"

    override suspend fun getByPlayerId(playerId: String, seasonId: String): List<PlayerAvailabilityResponse> {
        val response = client.get("$apiUrl/$tableName") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("player_id", "eq.$playerId")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "matchday_number.asc")
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
        val response = client.get("$apiUrl/$tableName") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "player_id.asc,matchday_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityResponse>>(bodyText)
        } else {
            logger.error("Failed to get availability by season: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getBySeasonAndMatchday(seasonId: String, matchdayNumber: Int): List<PlayerAvailabilityResponse> {
        val response = client.get("$apiUrl/$tableName") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("matchday_number", "eq.$matchdayNumber")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerAvailabilityResponse>>(bodyText)
        } else {
            logger.error("Failed to get availability by season and matchday: ${response.status}")
            emptyList()
        }
    }

    override suspend fun create(request: CreatePlayerAvailabilityRequest): PlayerAvailabilityResponse? {
        val payload = buildJsonObject {
            put("player_id", request.playerId)
            put("season_id", request.seasonId)
            put("matchday_number", request.matchdayNumber)
            put("available_time_slots", JsonArray(request.availableTimeSlots.map { JsonPrimitive(it) }))
        }

        val response = client.post("$apiUrl/$tableName") {
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

        val response = client.patch("$apiUrl/$tableName?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val response = client.delete("$apiUrl/$tableName?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
        }

        return response.status.isSuccess()
    }

    override suspend fun upsertBatch(request: BatchPlayerAvailabilityRequest): Boolean {
        // First delete all existing availability for this player+season
        val deleteResponse = client.delete("$apiUrl/$tableName") {
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

        val payload = request.availabilities.map { matchdayAvailability ->
            buildJsonObject {
                put("player_id", request.playerId)
                put("season_id", request.seasonId)
                put("matchday_number", matchdayAvailability.matchdayNumber)
                put("available_time_slots", JsonArray(matchdayAvailability.availableTimeSlots.map { JsonPrimitive(it) }))
            }
        }

        val response = client.post("$apiUrl/$tableName") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        return response.status.isSuccess()
    }

    override suspend fun getPlayerAvailabilitySummary(
        playerId: String,
        seasonId: String
    ): PlayerAvailabilitySummary? {
        // Get player info
        val player = leaguePlayerRepository.getById(playerId) ?: return null

        // Get matchday availability
        val matchdayAvailability = getByPlayerId(playerId, seasonId)
        val matchdayMap = matchdayAvailability.associate { it.matchdayNumber to it.availableTimeSlots }

        return PlayerAvailabilitySummary(
            playerId = playerId,
            playerName = player.name,
            matchdayAvailability = matchdayMap
        )
    }

    override suspend fun getAvailabilityForSlot(
        categoryId: String,
        seasonId: String,
        matchdayNumber: Int,
        timeSlot: String
    ): SlotAvailabilityResponse {
        // Get all players in the category
        val players = leaguePlayerRepository.getByCategoryId(categoryId)
            .filter { !it.isWaitingList }

        // Get all availability for this matchday
        val matchdayAvailability = getBySeasonAndMatchday(seasonId, matchdayNumber)
        val availabilityByPlayer = matchdayAvailability.associateBy { it.playerId }

        val availablePlayers = mutableListOf<AvailablePlayer>()
        val unavailablePlayers = mutableListOf<UnavailablePlayer>()

        for (player in players) {
            val availability = availabilityByPlayer[player.id]

            val isAvailable = when {
                // Player has set availability for this matchday
                availability != null -> timeSlot in availability.availableTimeSlots
                // No availability data = assume available (hasn't set restrictions)
                else -> true
            }

            if (isAvailable) {
                availablePlayers.add(AvailablePlayer(player.id, player.name))
            } else {
                unavailablePlayers.add(UnavailablePlayer(player.id, player.name, "No disponible en este horario"))
            }
        }

        return SlotAvailabilityResponse(
            timeSlot = timeSlot,
            matchdayNumber = matchdayNumber,
            availablePlayers = availablePlayers,
            unavailablePlayers = unavailablePlayers
        )
    }
}
