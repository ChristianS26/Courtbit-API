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
 * Service for handling user score submissions with audit trail.
 * Trusts authenticated users and logs their identity for accountability.
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
     * Submit a score as an authenticated user.
     * Validates that the season allows user score entry.
     * Creates audit trail in score_history with user info.
     */
    suspend fun submitScore(
        matchId: String,
        userId: String,
        userName: String,
        scoreTeam1: Int,
        scoreTeam2: Int
    ): UserScoreResult {
        logger.info("🎯 [PlayerScoreService] User $userName ($userId) submitting score for match $matchId")

        // 1. Get match info to check season settings
        val matchInfo = getMatchInfo(matchId)
            ?: return UserScoreResult.Error("Match not found")

        // 2. Check if season allows user scores
        val seasonAllowsScores = checkSeasonAllowsPlayerScores(matchInfo.seasonId)
        if (!seasonAllowsScores) {
            logger.warn("⚠️ [PlayerScoreService] Season ${matchInfo.seasonId} does not allow user scores")
            return UserScoreResult.Error("Score entry is disabled for this league")
        }

        // 3. Log to score_history (audit trail)
        val historyCreated = createScoreHistory(
            matchId = matchId,
            oldScoreTeam1 = matchInfo.scoreTeam1,
            oldScoreTeam2 = matchInfo.scoreTeam2,
            newScoreTeam1 = scoreTeam1,
            newScoreTeam2 = scoreTeam2,
            changedByUserId = userId,
            changedByName = userName
        )

        if (!historyCreated) {
            logger.error("❌ [PlayerScoreService] Failed to create score history")
            return UserScoreResult.Error("Failed to record score change")
        }

        // 4. Update the score with audit fields
        val updated = updateScoreWithAudit(
            matchId = matchId,
            scoreTeam1 = scoreTeam1,
            scoreTeam2 = scoreTeam2,
            submittedByUserId = userId
        )

        return if (updated) {
            logger.info("✅ [PlayerScoreService] Score updated successfully by $userName")
            UserScoreResult.Success
        } else {
            logger.error("❌ [PlayerScoreService] Failed to update score")
            UserScoreResult.Error("Failed to update score")
        }
    }

    /**
     * Check if a season allows user score entry
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

    private suspend fun getMatchInfo(matchId: String): MatchInfo? {
        // Join through rotation -> day_group -> match_day -> category to get season_id
        val selectQuery = """
            id,
            score_team1,
            score_team2,
            rotation:rotations!inner(
                day_group:day_groups!inner(
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
            val matches = json.decodeFromString<List<MatchWithSeasonRaw>>(bodyText)
            matches.firstOrNull()?.toMatchInfo()
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
        changedByUserId: String,
        changedByName: String
    ): Boolean {
        val payload = buildJsonObject {
            put("doubles_match_id", matchId)
            oldScoreTeam1?.let { put("old_score_team1", it) }
            oldScoreTeam2?.let { put("old_score_team2", it) }
            put("new_score_team1", newScoreTeam1)
            put("new_score_team2", newScoreTeam2)
            put("changed_by_player_id", changedByUserId) // Using same column, stores user ID
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
        submittedByUserId: String
    ): Boolean {
        val payload = buildJsonObject {
            put("score_team1", scoreTeam1)
            put("score_team2", scoreTeam2)
            put("submitted_by_player_id", submittedByUserId) // Using same column, stores user ID
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
sealed class UserScoreResult {
    object Success : UserScoreResult()
    data class Error(val message: String) : UserScoreResult()
}

// DTOs for parsing nested response
@Serializable
private data class SeasonScoreSettings(
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean?
)

@Serializable
private data class MatchWithSeasonRaw(
    val id: String,
    @SerialName("score_team1") val scoreTeam1: Int?,
    @SerialName("score_team2") val scoreTeam2: Int?,
    val rotation: RotationWithSeasonRaw
) {
    fun toMatchInfo() = MatchInfo(
        matchId = id,
        scoreTeam1 = scoreTeam1,
        scoreTeam2 = scoreTeam2,
        seasonId = rotation.dayGroup.matchDay.category.seasonId
    )
}

@Serializable
private data class RotationWithSeasonRaw(
    @SerialName("day_group") val dayGroup: DayGroupWithSeasonRaw
)

@Serializable
private data class DayGroupWithSeasonRaw(
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

data class MatchInfo(
    val matchId: String,
    val scoreTeam1: Int?,
    val scoreTeam2: Int?,
    val seasonId: String
)
