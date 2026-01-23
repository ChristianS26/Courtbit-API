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
import models.league.DayGroupResponse
import models.league.LeaguePlayerResponse
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
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("match_day_id", "eq.$matchDayId")
            parameter("order", "group_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<DayGroupRaw>>(bodyText)

            // Enrich with players
            rawList.map { raw ->
                val players = raw.playerIds.mapNotNull { playerId ->
                    fetchPlayerById(playerId)
                }
                raw.toDayGroupResponse(players)
            }
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): DayGroupResponse? {
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$id")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<DayGroupRaw>>(bodyText)
            val raw = rawList.firstOrNull() ?: return null

            // Enrich with players
            val players = raw.playerIds.mapNotNull { playerId ->
                fetchPlayerById(playerId)
            }
            raw.toDayGroupResponse(players)
        } else {
            null
        }
    }

    private suspend fun fetchPlayerById(playerId: String): LeaguePlayerResponse? {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$playerId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            rawList.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun updateAssignment(id: String, request: UpdateDayGroupAssignmentRequest): Boolean {
        // Build JSON manually to include explicit nulls (needed for clearing assignments)
        // The global Json config has explicitNulls=false which would skip null values
        val jsonBody = buildJsonObject {
            put("match_date", request.matchDate?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("time_slot", request.timeSlot?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("court_index", request.courtIndex?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
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

    override suspend fun findBySlot(matchDate: String, timeSlot: String, courtIndex: Int): DayGroupResponse? {
        val response = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("match_date", "eq.$matchDate")
            parameter("time_slot", "eq.$timeSlot")
            parameter("court_index", "eq.$courtIndex")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<DayGroupRaw>>(bodyText)
            val raw = rawList.firstOrNull() ?: return null

            // Enrich with players
            val players = raw.playerIds.mapNotNull { playerId ->
                fetchPlayerById(playerId)
            }
            raw.toDayGroupResponse(players)
        } else {
            null
        }
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
        logger.info("🔄 [DayGroupRepo] Regenerating rotations for dayGroupId=$dayGroupId")

        // 1. Get the day group to check player_ids
        val dayGroup = getById(dayGroupId)
            ?: return RegenerateResult.Error("Day group not found")

        // 2. Check if we have exactly 4 players
        if (dayGroup.playerIds.size != 4) {
            logger.warn("⚠️ [DayGroupRepo] Day group has ${dayGroup.playerIds.size} players, need exactly 4")
            return RegenerateResult.NotEnoughPlayers
        }

        // 3. Check if rotations already exist
        val existingCount = getRotationCount(dayGroupId)
        if (existingCount > 0) {
            logger.info("ℹ️ [DayGroupRepo] Day group already has $existingCount rotations")
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
                logger.error("❌ [DayGroupRepo] Failed to create rotation $rotationNumber: ${rotationResponse.status}")
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
                logger.error("❌ [DayGroupRepo] Failed to create match for rotation $rotationNumber: ${matchResponse.status}")
                return RegenerateResult.Error("Failed to create match for rotation $rotationNumber")
            }

            logger.info("✅ [DayGroupRepo] Created rotation $rotationNumber with match")
        }

        logger.info("✅ [DayGroupRepo] Successfully regenerated all rotations for dayGroupId=$dayGroupId")
        return RegenerateResult.Success
    }

    /**
     * Phase 3.1: Clear all assignments for a matchday
     * Clears all day_groups for a specific season and matchday number
     */
    override suspend fun clearMatchdayAssignments(seasonId: String, matchdayNumber: Int): Int {
        logger.info("🧹 [DayGroupRepo] Clearing matchday assignments for seasonId=$seasonId, matchday=$matchdayNumber")

        // Step 1: Get all category IDs for this season
        @Serializable
        data class CategoryId(val id: String)

        val categoriesResponse = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("season_id", "eq.$seasonId")
        }

        if (!categoriesResponse.status.isSuccess()) {
            logger.error("❌ [DayGroupRepo] Failed to get categories for season: ${categoriesResponse.status}")
            return 0
        }

        val categoryIds = json.decodeFromString<List<CategoryId>>(categoriesResponse.bodyAsText())
            .map { it.id }

        if (categoryIds.isEmpty()) {
            logger.info("ℹ️ [DayGroupRepo] No categories found for season $seasonId")
            return 0
        }

        logger.info("📋 [DayGroupRepo] Found ${categoryIds.size} categories for season")

        // Step 2: Get match_days for these categories with the specified matchday_number
        @Serializable
        data class MatchDayId(val id: String)

        val categoryIdsFilter = categoryIds.joinToString(",") { "\"$it\"" }
        val matchDaysResponse = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("category_id", "in.($categoryIdsFilter)")
            parameter("match_number", "eq.$matchdayNumber")
        }

        if (!matchDaysResponse.status.isSuccess()) {
            logger.error("❌ [DayGroupRepo] Failed to get match days: ${matchDaysResponse.status}")
            return 0
        }

        val matchDayIds = json.decodeFromString<List<MatchDayId>>(matchDaysResponse.bodyAsText())
            .map { it.id }

        if (matchDayIds.isEmpty()) {
            logger.info("ℹ️ [DayGroupRepo] No match days found for matchday $matchdayNumber in this season")
            return 0
        }

        logger.info("📅 [DayGroupRepo] Found ${matchDayIds.size} match days to clear")

        // Step 3: Get and clear day_groups for these match_days
        var totalCleared = 0

        for (matchDayId in matchDayIds) {
            // Get day_groups with assignments for this match_day
            val dayGroupsResponse = client.get("$apiUrl/day_groups") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "id")
                parameter("match_day_id", "eq.$matchDayId")
                parameter("match_date", "not.is.null")
            }

            if (!dayGroupsResponse.status.isSuccess()) {
                continue
            }

            @Serializable
            data class DayGroupId(val id: String)

            val dayGroupIds = json.decodeFromString<List<DayGroupId>>(dayGroupsResponse.bodyAsText())

            // Clear each day_group's assignment
            for (dayGroup in dayGroupIds) {
                val clearResponse = client.patch("$apiUrl/day_groups?id=eq.${dayGroup.id}") {
                    header("apikey", apiKey)
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("match_date", null as String?)
                        put("time_slot", null as String?)
                        put("court_index", null as Int?)
                    }.toString())
                }

                if (clearResponse.status.isSuccess()) {
                    totalCleared++
                }
            }
        }

        logger.info("✅ [DayGroupRepo] Cleared $totalCleared day_group assignments")
        return totalCleared
    }
}

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
        createdAt = createdAt,
        players = players
    )
}
