package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import models.league.DayGroupResponse
import models.league.LeaguePlayerResponse
import models.league.SlotAssignmentResult
import models.league.UpdateDayGroupAssignmentRequest
import org.slf4j.LoggerFactory

class DayGroupRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DayGroupRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(DayGroupRepositoryImpl::class.java)

    override suspend fun getByMatchDayId(matchDayId: String): List<DayGroupResponse> {
        // 1 query: fetch day_groups with embedded court data via FK
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,season_courts(id,name,court_number)")
            parameter("match_day_id", "eq.$matchDayId")
            parameter("order", "group_number.asc")
        }

        if (!response.status.isSuccess()) return emptyList()

        val rawList = json.decodeFromString<List<DayGroupRaw>>(response.bodyAsText())
        if (rawList.isEmpty()) return emptyList()

        // Collect all unique player IDs across all groups
        val allPlayerIds = rawList.flatMap { it.playerIds }.distinct()

        // 1 query: batch-fetch all players at once
        val playerMap = fetchPlayersByIds(allPlayerIds)

        // Enrich each group from the shared player map + embedded court
        return rawList.map { raw ->
            val players = raw.playerIds.mapNotNull { playerMap[it] }
            raw.toDayGroupResponse(players)
        }
    }

    override suspend fun getById(id: String): DayGroupResponse? {
        // 1 query: fetch day_group with embedded court data
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,season_courts(id,name,court_number)")
            parameter("id", "eq.$id")
            parameter("limit", "1")
        }

        if (!response.status.isSuccess()) return null

        val rawList = json.decodeFromString<List<DayGroupRaw>>(response.bodyAsText())
        val raw = rawList.firstOrNull() ?: return null

        // 1 query: batch-fetch players for this group
        val playerMap = fetchPlayersByIds(raw.playerIds)
        val players = raw.playerIds.mapNotNull { playerMap[it] }
        return raw.toDayGroupResponse(players)
    }

    override suspend fun findBySlot(matchDate: String, timeSlot: String, courtIndex: Int): DayGroupResponse? {
        // 1 query: fetch day_group with embedded court data
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,season_courts(id,name,court_number)")
            parameter("match_date", "eq.$matchDate")
            parameter("time_slot", "eq.$timeSlot")
            parameter("court_index", "eq.$courtIndex")
            parameter("limit", "1")
        }

        if (!response.status.isSuccess()) return null

        val rawList = json.decodeFromString<List<DayGroupRaw>>(response.bodyAsText())
        val raw = rawList.firstOrNull() ?: return null

        // 1 query: batch-fetch players for this group
        val playerMap = fetchPlayersByIds(raw.playerIds)
        val players = raw.playerIds.mapNotNull { playerMap[it] }
        return raw.toDayGroupResponse(players)
    }

    /**
     * Batch-fetch players by a list of IDs in a single query.
     * Uses Supabase `in` filter: ?id=in.(id1,id2,...)
     */
    private suspend fun fetchPlayersByIds(playerIds: List<String>): Map<String, LeaguePlayerResponse> {
        if (playerIds.isEmpty()) return emptyMap()

        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "in.(${playerIds.joinToString(",")})")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<LeaguePlayerResponse>>(response.bodyAsText())
                .associateBy { it.id }
        } else {
            emptyMap()
        }
    }

    override suspend fun updateAssignment(id: String, request: UpdateDayGroupAssignmentRequest): Boolean {
        // Build JSON manually to include explicit nulls (needed for clearing assignments)
        // The global Json config has explicitNulls=false which would skip null values
        val jsonBody = buildJsonObject {
            put("match_date", request.matchDate?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("time_slot", request.timeSlot?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("court_index", request.courtIndex?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("court_id", request.courtId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }

        val response = client.patch("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(jsonBody.toString())
        }

        return response.status.isSuccess()
    }

    override suspend fun assignSlot(dayGroupId: String, request: UpdateDayGroupAssignmentRequest): Result<SlotAssignmentResult> {
        val payload = buildJsonObject {
            put("p_day_group_id", JsonPrimitive(dayGroupId))
            request.matchDate?.let { put("p_match_date", JsonPrimitive(it)) }
            request.timeSlot?.let { put("p_time_slot", JsonPrimitive(it)) }
            request.courtIndex?.let { put("p_court_index", JsonPrimitive(it)) }
            request.courtId?.let { put("p_court_id", JsonPrimitive(it)) }
        }

        val response = client.post("$apiUrl/rpc/assign_day_group_slot") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            return Result.failure(IllegalStateException("Failed to assign slot: $errorBody"))
        }

        val result = json.decodeFromString<SlotAssignmentResult>(response.bodyAsText())
        return Result.success(result)
    }

    override suspend fun getRotationCount(dayGroupId: String): Int {
        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("day_group_id", "eq.$dayGroupId")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rotations = json.decodeFromString<List<Map<String, String>>>(bodyText)
            rotations.size
        } else {
            0
        }
    }

    override suspend fun regenerateRotations(dayGroupId: String): RegenerateResult {
        logger.info("[DayGroupRepo] Regenerating rotations for dayGroupId=$dayGroupId")

        // 1. Get the day group to check player_ids
        val dayGroup = getById(dayGroupId)
            ?: return RegenerateResult.Error("Day group not found")

        // 2. Check if we have exactly 4 players
        if (dayGroup.playerIds.size != 4) {
            logger.warn("[DayGroupRepo] Day group has ${dayGroup.playerIds.size} players, need exactly 4")
            return RegenerateResult.NotEnoughPlayers
        }

        // 3. Check if rotations already exist
        val existingCount = getRotationCount(dayGroupId)
        if (existingCount > 0) {
            logger.info("[DayGroupRepo] Day group already has $existingCount rotations")
            return RegenerateResult.AlreadyExists
        }

        // 4. Create 3 rotations with matches
        // Standard padel rotation for 4 players:
        // Rotation 1: P0+P1 vs P2+P3
        // Rotation 2: P0+P2 vs P1+P3
        // Rotation 3: P0+P3 vs P1+P2
        val players = dayGroup.playerIds
        val rotationPairings = listOf(
            Triple(1, Pair(players[0], players[1]), Pair(players[2], players[3])),
            Triple(2, Pair(players[0], players[2]), Pair(players[1], players[3])),
            Triple(3, Pair(players[0], players[3]), Pair(players[1], players[2]))
        )

        for ((rotationNumber, team1, team2) in rotationPairings) {
            // Create rotation
            val rotationPayload = buildJsonObject {
                put("day_group_id", dayGroupId)
                put("rotation_number", rotationNumber)
            }

            val rotationResponse = client.post("$apiUrl/rotations") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(rotationPayload.toString())
            }

            if (!rotationResponse.status.isSuccess()) {
                logger.error("[DayGroupRepo] Failed to create rotation $rotationNumber: ${rotationResponse.status}")
                return RegenerateResult.Error("Failed to create rotation $rotationNumber")
            }

            // Get the created rotation ID
            val rotationBody = rotationResponse.bodyAsText()
            val createdRotations = json.decodeFromString<List<RotationCreated>>(rotationBody)
            val rotationId = createdRotations.firstOrNull()?.id
                ?: return RegenerateResult.Error("Failed to get rotation ID")

            // Create match for this rotation
            val matchPayload = buildJsonObject {
                put("rotation_id", rotationId)
                put("team1_player1_id", team1.first)
                put("team1_player2_id", team1.second)
                put("team2_player1_id", team2.first)
                put("team2_player2_id", team2.second)
            }

            val matchResponse = client.post("$apiUrl/doubles_matches") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(matchPayload.toString())
            }

            if (!matchResponse.status.isSuccess()) {
                logger.error("[DayGroupRepo] Failed to create match for rotation $rotationNumber: ${matchResponse.status}")
                return RegenerateResult.Error("Failed to create match for rotation $rotationNumber")
            }

            logger.info("[DayGroupRepo] Created rotation $rotationNumber with match")
        }

        logger.info("[DayGroupRepo] Successfully regenerated all rotations for dayGroupId=$dayGroupId")
        return RegenerateResult.Success
    }

    /**
     * Clear all assignments for a matchday via single RPC call.
     * Before: 2 + N + M queries. After: 1 RPC call.
     */
    override suspend fun clearMatchdayAssignments(seasonId: String, matchdayNumber: Int): Int {
        logger.info("[DayGroupRepo] Clearing matchday assignments for seasonId=$seasonId, matchday=$matchdayNumber")

        val payload = buildJsonObject {
            put("p_season_id", JsonPrimitive(seasonId))
            put("p_matchday_number", JsonPrimitive(matchdayNumber))
        }

        val response = client.post("$apiUrl/rpc/clear_matchday_assignments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("[DayGroupRepo] RPC clear_matchday_assignments failed: $errorBody")
            return 0
        }

        val result = json.decodeFromString<ClearAssignmentsResult>(response.bodyAsText())
        logger.info("[DayGroupRepo] Cleared ${result.clearedCount} day_group assignments")
        return result.clearedCount
    }
}

@Serializable
private data class ClearAssignmentsResult(
    @SerialName("cleared_count") val clearedCount: Int
)

@Serializable
private data class RotationCreated(
    val id: String
)

@Serializable
private data class DayGroupRaw(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("time_slot") val timeSlot: String? = null,
    @SerialName("court_index") val courtIndex: Int? = null,
    @SerialName("court_id") val courtId: String? = null,
    @SerialName("season_courts") val seasonCourt: CourtInfo? = null,
    @SerialName("created_at") val createdAt: String
) {
    fun toDayGroupResponse(players: List<LeaguePlayerResponse>) = DayGroupResponse(
        id = id,
        matchDayId = matchDayId,
        groupNumber = groupNumber,
        playerIds = playerIds,
        matchDate = matchDate,
        timeSlot = timeSlot,
        courtIndex = courtIndex,
        courtId = courtId,
        courtName = seasonCourt?.name,
        courtNumber = seasonCourt?.courtNumber,
        createdAt = createdAt,
        players = players
    )
}

@Serializable
private data class CourtInfo(
    val id: String,
    val name: String,
    @SerialName("court_number") val courtNumber: Int
)
