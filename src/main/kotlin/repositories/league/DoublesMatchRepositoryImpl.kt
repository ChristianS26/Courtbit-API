package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
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
import models.league.DoublesMatchResponse
import models.league.LeaguePlayerResponse
import models.league.UpdateMatchScoreRequest
import org.slf4j.LoggerFactory

class DoublesMatchRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DoublesMatchRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(DoublesMatchRepositoryImpl::class.java)

    override suspend fun getById(id: String): DoublesMatchResponse? {
        logger.info("🔍 [DoublesMatchRepo] getById($id)")

        val response = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$id")
            parameter("limit", "1")
        }

        val bodyText = response.bodyAsText()
        logger.info("🔍 [DoublesMatchRepo] Response: status=${response.status}, body=$bodyText")

        return if (response.status.isSuccess()) {
            val rawList = json.decodeFromString<List<DoublesMatchRaw>>(bodyText)
            val raw = rawList.firstOrNull()

            if (raw == null) {
                logger.warn("⚠️ [DoublesMatchRepo] No match found for id=$id")
                return null
            }

            logger.info("🔍 [DoublesMatchRepo] Found match, enriching with players...")

            // Enrich with players
            val team1Player1 = raw.team1Player1Id?.let { fetchPlayerById(it) }
            val team1Player2 = raw.team1Player2Id?.let { fetchPlayerById(it) }
            val team2Player1 = raw.team2Player1Id?.let { fetchPlayerById(it) }
            val team2Player2 = raw.team2Player2Id?.let { fetchPlayerById(it) }

            raw.toDoublesMatchResponse(team1Player1, team1Player2, team2Player1, team2Player2)
        } else {
            logger.error("❌ [DoublesMatchRepo] Failed to fetch match: status=${response.status}")
            null
        }
    }

    override suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest, submittedByName: String?): Boolean {
        val isReset = request.scoreTeam1 == 0 && request.scoreTeam2 == 0
        logger.info("📝 [DoublesMatchRepo] updateScore($matchId, ${request.scoreTeam1}-${request.scoreTeam2}, isReset=$isReset)")

        val payload = buildJsonObject {
            if (isReset) {
                // Reset: set scores to null so match appears as "not played"
                put("score_team1", kotlinx.serialization.json.JsonNull)
                put("score_team2", kotlinx.serialization.json.JsonNull)
                // Clear submitter info and forfeit data
                put("submitted_by_name", kotlinx.serialization.json.JsonNull)
                put("submitted_at", kotlinx.serialization.json.JsonNull)
                put("is_forfeit", false)
                put("forfeited_player_ids", kotlinx.serialization.json.JsonArray(emptyList()))
                put("forfeit_recorded_by_uid", kotlinx.serialization.json.JsonNull)
                put("forfeit_recorded_at", kotlinx.serialization.json.JsonNull)
            } else {
                // Normal score update
                put("score_team1", request.scoreTeam1)
                put("score_team2", request.scoreTeam2)
                if (submittedByName != null) {
                    put("submitted_by_name", submittedByName)
                    put("submitted_at", java.time.Instant.now().toString())
                }
            }
        }

        val response = client.patch("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$matchId")
            setBody(payload.toString())
        }

        val success = response.status.isSuccess()
        logger.info("📝 [DoublesMatchRepo] updateScore response: status=${response.status}, success=$success")
        if (!success) {
            logger.error("❌ [DoublesMatchRepo] updateScore failed: ${response.bodyAsText()}")
        }
        return success
    }

    override suspend fun markForfeit(
        matchId: String,
        forfeitedPlayerIds: List<String>,
        recordedByUid: String
    ): Boolean {
        // Points are assigned based on season forfeit settings during ranking calculation
        // We only record which players forfeited here
        logger.info("🏳️ [DoublesMatchRepo] markForfeit($matchId, players=$forfeitedPlayerIds)")

        val payload = buildJsonObject {
            put("is_forfeit", true)
            put("forfeited_player_ids", kotlinx.serialization.json.JsonArray(
                forfeitedPlayerIds.map { kotlinx.serialization.json.JsonPrimitive(it) }
            ))
            put("forfeit_recorded_by_uid", recordedByUid)
            put("forfeit_recorded_at", java.time.Instant.now().toString())
        }

        val response = client.patch("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$matchId")
            setBody(payload.toString())
        }

        logger.info("🏳️ [DoublesMatchRepo] markForfeit response: status=${response.status}")
        return response.status.isSuccess()
    }

    override suspend fun reverseForfeit(matchId: String, clearScores: Boolean): Boolean {
        logger.info("↩️ [DoublesMatchRepo] reverseForfeit($matchId, clearScores=$clearScores)")

        val payload = buildJsonObject {
            put("is_forfeit", false)
            put("forfeited_player_ids", kotlinx.serialization.json.JsonArray(emptyList()))
            put("forfeit_recorded_by_uid", kotlinx.serialization.json.JsonNull)
            put("forfeit_recorded_at", kotlinx.serialization.json.JsonNull)
            if (clearScores) {
                put("score_team1", kotlinx.serialization.json.JsonNull)
                put("score_team2", kotlinx.serialization.json.JsonNull)
            }
        }

        val response = client.patch("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$matchId")
            setBody(payload.toString())
        }

        logger.info("↩️ [DoublesMatchRepo] reverseForfeit response: status=${response.status}")
        return response.status.isSuccess()
    }

    override suspend fun getSeasonMaxPointsForMatch(matchId: String): Int {
        logger.info("🔍 [DoublesMatchRepo] getSeasonMaxPointsForMatch($matchId)")

        // Step 1: Get the match to find its rotation_id
        val matchResponse = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "rotation_id")
            parameter("id", "eq.$matchId")
            parameter("limit", "1")
        }

        if (!matchResponse.status.isSuccess()) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to fetch match, using default 6")
            return 6
        }

        val matchBody = matchResponse.bodyAsText()
        val rotationId = try {
            val matches = json.decodeFromString<List<MatchRotationId>>(matchBody)
            matches.firstOrNull()?.rotationId
        } catch (e: Exception) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to parse match: ${e.message}")
            null
        }

        if (rotationId == null) {
            logger.warn("⚠️ [DoublesMatchRepo] No rotation_id found, using default 6")
            return 6
        }

        // Step 2: Get the rotation to find day_group_id
        val rotationResponse = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "day_group_id")
            parameter("id", "eq.$rotationId")
            parameter("limit", "1")
        }

        if (!rotationResponse.status.isSuccess()) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to fetch rotation, using default 6")
            return 6
        }

        val rotationBody = rotationResponse.bodyAsText()
        val dayGroupId = try {
            val rotations = json.decodeFromString<List<RotationDayGroupId>>(rotationBody)
            rotations.firstOrNull()?.dayGroupId
        } catch (e: Exception) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to parse rotation: ${e.message}")
            null
        }

        if (dayGroupId == null) {
            logger.warn("⚠️ [DoublesMatchRepo] No day_group_id found, using default 6")
            return 6
        }

        // Step 3: Get day_group to find category_id
        val dayGroupResponse = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "category_id")
            parameter("id", "eq.$dayGroupId")
            parameter("limit", "1")
        }

        if (!dayGroupResponse.status.isSuccess()) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to fetch day_group, using default 6")
            return 6
        }

        val dayGroupBody = dayGroupResponse.bodyAsText()
        val categoryId = try {
            val dayGroups = json.decodeFromString<List<DayGroupCategoryId>>(dayGroupBody)
            dayGroups.firstOrNull()?.categoryId
        } catch (e: Exception) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to parse day_group: ${e.message}")
            null
        }

        if (categoryId == null) {
            logger.warn("⚠️ [DoublesMatchRepo] No category_id found, using default 6")
            return 6
        }

        // Step 4: Get category to find season_id
        val categoryResponse = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "season_id")
            parameter("id", "eq.$categoryId")
            parameter("limit", "1")
        }

        if (!categoryResponse.status.isSuccess()) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to fetch category, using default 6")
            return 6
        }

        val categoryBody = categoryResponse.bodyAsText()
        val seasonId = try {
            val categories = json.decodeFromString<List<CategorySeasonId>>(categoryBody)
            categories.firstOrNull()?.seasonId
        } catch (e: Exception) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to parse category: ${e.message}")
            null
        }

        if (seasonId == null) {
            logger.warn("⚠️ [DoublesMatchRepo] No season_id found, using default 6")
            return 6
        }

        // Step 5: Get season's max_points_per_game
        val seasonResponse = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "max_points_per_game")
            parameter("id", "eq.$seasonId")
            parameter("limit", "1")
        }

        if (!seasonResponse.status.isSuccess()) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to fetch season, using default 6")
            return 6
        }

        val seasonBody = seasonResponse.bodyAsText()
        logger.info("🔍 [DoublesMatchRepo] Season response: $seasonBody")

        return try {
            val seasons = json.decodeFromString<List<SeasonMaxPointsOnly>>(seasonBody)
            val maxPoints = seasons.firstOrNull()?.maxPointsPerGame ?: 6
            logger.info("🔍 [DoublesMatchRepo] maxPointsPerGame for match $matchId: $maxPoints")
            maxPoints
        } catch (e: Exception) {
            logger.warn("⚠️ [DoublesMatchRepo] Failed to parse season: ${e.message}")
            6
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
}

@Serializable
private data class DoublesMatchRaw(
    val id: String,
    @SerialName("rotation_id") val rotationId: String,
    @SerialName("team1_player1_id") val team1Player1Id: String? = null,
    @SerialName("team1_player2_id") val team1Player2Id: String? = null,
    @SerialName("team2_player1_id") val team2Player1Id: String? = null,
    @SerialName("team2_player2_id") val team2Player2Id: String? = null,
    @SerialName("score_team1") val scoreTeam1: Int? = null,
    @SerialName("score_team2") val scoreTeam2: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("is_forfeit") val isForfeit: Boolean = false,
    @SerialName("forfeited_player_ids") val forfeitedPlayerIds: List<String> = emptyList(),
    @SerialName("forfeit_recorded_by_uid") val forfeitRecordedByUid: String? = null,
    @SerialName("forfeit_recorded_at") val forfeitRecordedAt: String? = null
) {
    fun toDoublesMatchResponse(
        team1Player1: LeaguePlayerResponse?,
        team1Player2: LeaguePlayerResponse?,
        team2Player1: LeaguePlayerResponse?,
        team2Player2: LeaguePlayerResponse?
    ) = DoublesMatchResponse(
        id = id,
        rotationId = rotationId,
        team1Player1Id = team1Player1Id,
        team1Player2Id = team1Player2Id,
        team2Player1Id = team2Player1Id,
        team2Player2Id = team2Player2Id,
        scoreTeam1 = scoreTeam1,
        scoreTeam2 = scoreTeam2,
        createdAt = createdAt,
        updatedAt = updatedAt,
        team1Player1 = team1Player1,
        team1Player2 = team1Player2,
        team2Player1 = team2Player1,
        team2Player2 = team2Player2,
        submittedByName = submittedByName,
        submittedAt = submittedAt,
        isForfeit = isForfeit,
        forfeitedPlayerIds = forfeitedPlayerIds,
        forfeitRecordedByUid = forfeitRecordedByUid,
        forfeitRecordedAt = forfeitRecordedAt
    )
}

// Simple response models for step-by-step season config lookup
@Serializable
private data class MatchRotationId(
    @SerialName("rotation_id") val rotationId: String
)

@Serializable
private data class RotationDayGroupId(
    @SerialName("day_group_id") val dayGroupId: String
)

@Serializable
private data class DayGroupCategoryId(
    @SerialName("category_id") val categoryId: String
)

@Serializable
private data class CategorySeasonId(
    @SerialName("season_id") val seasonId: String
)

@Serializable
private data class SeasonMaxPointsOnly(
    @SerialName("max_points_per_game") val maxPointsPerGame: Int = 6
)
