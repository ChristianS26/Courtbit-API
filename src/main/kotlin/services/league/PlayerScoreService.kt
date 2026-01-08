package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
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
import org.slf4j.LoggerFactory

/**
 * Service for handling player score submissions with validation and audit trail
 */
class PlayerScoreService(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(PlayerScoreService::class.java)

    /**
     * Submit a score as a player
     * Validates:
     * - Player is part of the day_group for this match
     * - Season allows player score entry
     * Creates audit trail in score_history
     */
    suspend fun submitScore(
        matchId: String,
        playerId: String,
        playerName: String,
        scoreTeam1: Int,
        scoreTeam2: Int
    ): PlayerScoreResult {
        logger.info("🎯 [PlayerScoreService] Player $playerName submitting score for match $matchId")

        // 1. Get match with rotation and day_group info
        val matchInfo = getMatchWithGroupInfo(matchId)
            ?: return PlayerScoreResult.Error("Match not found")

        // 2. Check if player is in the day_group
        if (!matchInfo.playerIds.contains(playerId)) {
            logger.warn("⚠️ [PlayerScoreService] Player $playerId not in group ${matchInfo.dayGroupId}")
            return PlayerScoreResult.Error("You are not part of this group")
        }

        // 3. Check if season allows player scores
        val seasonAllowsPlayerScores = checkSeasonAllowsPlayerScores(matchInfo.seasonId)
        if (!seasonAllowsPlayerScores) {
            logger.warn("⚠️ [PlayerScoreService] Season ${matchInfo.seasonId} does not allow player scores")
            return PlayerScoreResult.Error("Player score entry is disabled for this league")
        }

        // 4. Log to score_history (before updating)
        val historyCreated = createScoreHistory(
            matchId = matchId,
            oldScoreTeam1 = matchInfo.scoreTeam1,
            oldScoreTeam2 = matchInfo.scoreTeam2,
            newScoreTeam1 = scoreTeam1,
            newScoreTeam2 = scoreTeam2,
            changedByPlayerId = playerId,
            changedByName = playerName
        )

        if (!historyCreated) {
            logger.error("❌ [PlayerScoreService] Failed to create score history")
            return PlayerScoreResult.Error("Failed to record score change")
        }

        // 5. Update the score with audit fields
        val updated = updateScoreWithAudit(
            matchId = matchId,
            scoreTeam1 = scoreTeam1,
            scoreTeam2 = scoreTeam2,
            submittedByPlayerId = playerId
        )

        return if (updated) {
            logger.info("✅ [PlayerScoreService] Score updated successfully")
            PlayerScoreResult.Success
        } else {
            logger.error("❌ [PlayerScoreService] Failed to update score")
            PlayerScoreResult.Error("Failed to update score")
        }
    }

    /**
     * Check if a season allows player score entry
     */
    suspend fun checkSeasonAllowsPlayerScores(seasonId: String): Boolean {
        val response = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "allow_player_scores")
            parameter("id", "eq.$seasonId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val seasons = json.decodeFromString<List<SeasonScoreSettings>>(bodyText)
            seasons.firstOrNull()?.allowPlayerScores ?: true // Default to true if not set
        } else {
            true // Default to allowing if we can't check
        }
    }

    private suspend fun getMatchWithGroupInfo(matchId: String): MatchGroupInfo? {
        // Join through rotation -> day_group -> match_day -> category -> season
        val selectQuery = """
            id,
            score_team1,
            score_team2,
            rotation:rotations!inner(
                day_group:day_groups!inner(
                    id,
                    player_ids,
                    match_day:match_days!inner(
                        category:league_categories!inner(
                            season_id
                        )
                    )
                )
            )
        """.trimIndent().replace("\n", "")

        val response = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectQuery)
            parameter("id", "eq.$matchId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            logger.debug("🔍 [PlayerScoreService] Match info response: $bodyText")
            val matches = json.decodeFromString<List<MatchWithGroupRaw>>(bodyText)
            matches.firstOrNull()?.toMatchGroupInfo()
        } else {
            logger.error("❌ [PlayerScoreService] Failed to get match info: ${response.status}")
            null
        }
    }

    private suspend fun createScoreHistory(
        matchId: String,
        oldScoreTeam1: Int?,
        oldScoreTeam2: Int?,
        newScoreTeam1: Int,
        newScoreTeam2: Int,
        changedByPlayerId: String,
        changedByName: String
    ): Boolean {
        val payload = buildJsonObject {
            put("doubles_match_id", matchId)
            oldScoreTeam1?.let { put("old_score_team1", it) }
            oldScoreTeam2?.let { put("old_score_team2", it) }
            put("new_score_team1", newScoreTeam1)
            put("new_score_team2", newScoreTeam2)
            put("changed_by_player_id", changedByPlayerId)
            put("changed_by_name", changedByName)
        }

        val response = client.post("$apiUrl/score_history") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        return response.status.isSuccess()
    }

    private suspend fun updateScoreWithAudit(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        submittedByPlayerId: String
    ): Boolean {
        val payload = buildJsonObject {
            put("score_team1", scoreTeam1)
            put("score_team2", scoreTeam2)
            put("submitted_by_player_id", submittedByPlayerId)
            put("submitted_at", java.time.Instant.now().toString())
        }

        val response = client.patch("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$matchId")
            setBody(payload.toString())
        }

        return response.status.isSuccess()
    }
}

// Result type for score submission
sealed class PlayerScoreResult {
    object Success : PlayerScoreResult()
    data class Error(val message: String) : PlayerScoreResult()
}

// DTOs for parsing nested response
@Serializable
private data class SeasonScoreSettings(
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean?
)

@Serializable
private data class MatchWithGroupRaw(
    val id: String,
    @SerialName("score_team1") val scoreTeam1: Int?,
    @SerialName("score_team2") val scoreTeam2: Int?,
    val rotation: RotationWithGroupRaw
) {
    fun toMatchGroupInfo() = MatchGroupInfo(
        matchId = id,
        scoreTeam1 = scoreTeam1,
        scoreTeam2 = scoreTeam2,
        dayGroupId = rotation.dayGroup.id,
        playerIds = rotation.dayGroup.playerIds,
        seasonId = rotation.dayGroup.matchDay.category.seasonId
    )
}

@Serializable
private data class RotationWithGroupRaw(
    @SerialName("day_group") val dayGroup: DayGroupWithMatchDayRaw
)

@Serializable
private data class DayGroupWithMatchDayRaw(
    val id: String,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_day") val matchDay: MatchDayWithCategoryRaw
)

@Serializable
private data class MatchDayWithCategoryRaw(
    val category: CategoryWithSeasonRaw
)

@Serializable
private data class CategoryWithSeasonRaw(
    @SerialName("season_id") val seasonId: String
)

data class MatchGroupInfo(
    val matchId: String,
    val scoreTeam1: Int?,
    val scoreTeam2: Int?,
    val dayGroupId: String,
    val playerIds: List<String>,
    val seasonId: String
)
