package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.league.DoublesMatchResponse
import models.league.LeaguePlayerResponse
import models.league.RotationResponse
import org.slf4j.LoggerFactory

class RotationRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val doublesMatchRepository: DoublesMatchRepository
) : RotationRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(RotationRepositoryImpl::class.java)

    /**
     * OPTIMIZED: Fetches rotations with all nested data in a SINGLE query
     * Previously: 19+ sequential requests (N+1 problem causing timeouts)
     * Now: 1 request using Supabase nested selects with foreign key joins
     */
    override suspend fun getByDayGroupId(dayGroupId: String): List<RotationResponse> {
        logger.info("🔍 [RotationRepo] getByDayGroupId($dayGroupId) - OPTIMIZED single query")

        // Use Supabase nested select to fetch rotations with matches and players in ONE query
        // This replaces 19+ sequential queries with a single optimized request
        val selectQuery = buildString {
            append("*,")
            append("doubles_matches(")
            append("*,")
            append("team1_player1:league_players!doubles_matches_team1_player1_id_fkey(*),")
            append("team1_player2:league_players!doubles_matches_team1_player2_id_fkey(*),")
            append("team2_player1:league_players!doubles_matches_team2_player1_id_fkey(*),")
            append("team2_player2:league_players!doubles_matches_team2_player2_id_fkey(*)")
            append(")")
        }

        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectQuery)
            parameter("day_group_id", "eq.$dayGroupId")
            parameter("order", "rotation_number.asc")
        }

        val bodyText = response.bodyAsText()
        logger.info("🔍 [RotationRepo] Response status: ${response.status}, body length: ${bodyText.length}")

        return if (response.status.isSuccess()) {
            try {
                val rawList = json.decodeFromString<List<RotationWithMatchRaw>>(bodyText)
                logger.info("🔍 [RotationRepo] Found ${rawList.size} rotations for dayGroupId=$dayGroupId")
                rawList.map { it.toRotationResponse() }
            } catch (e: Exception) {
                logger.error("❌ [RotationRepo] Failed to parse response: ${e.message}, body: $bodyText")
                // Fallback to legacy N+1 approach if new format fails
                logger.warn("⚠️ [RotationRepo] Falling back to legacy N+1 queries")
                getByDayGroupIdLegacy(dayGroupId)
            }
        } else {
            logger.error("❌ [RotationRepo] Failed to fetch rotations: status=${response.status}, body=$bodyText")
            emptyList()
        }
    }

    /**
     * Legacy N+1 query approach - kept as fallback
     */
    private suspend fun getByDayGroupIdLegacy(dayGroupId: String): List<RotationResponse> {
        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("day_group_id", "eq.$dayGroupId")
            parameter("order", "rotation_number.asc")
        }

        val bodyText = response.bodyAsText()
        return if (response.status.isSuccess()) {
            val rawList = json.decodeFromString<List<RotationRaw>>(bodyText)
            rawList.map { raw ->
                val match = fetchMatchByRotationId(raw.id)
                raw.toRotationResponse(match)
            }
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): RotationResponse? {
        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$id")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<RotationRaw>>(bodyText)
            val raw = rawList.firstOrNull() ?: return null

            // Enrich with match
            val match = fetchMatchByRotationId(raw.id)
            raw.toRotationResponse(match)
        } else {
            null
        }
    }

    private suspend fun fetchMatchByRotationId(rotationId: String): DoublesMatchResponse? {
        logger.info("🔍 [RotationRepo] fetchMatchByRotationId($rotationId)")

        // Query by rotation_id
        val response = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("rotation_id", "eq.$rotationId")
            parameter("limit", "1")
        }

        val bodyText = response.bodyAsText()
        logger.info("🔍 [RotationRepo] doubles_matches response: status=${response.status}, body=$bodyText")

        return if (response.status.isSuccess()) {
            val idList = json.decodeFromString<List<Map<String, String>>>(bodyText)
            val matchId = idList.firstOrNull()?.get("id")

            if (matchId == null) {
                logger.warn("⚠️ [RotationRepo] No match found for rotationId=$rotationId")
                return null
            }

            logger.info("🔍 [RotationRepo] Found matchId=$matchId, fetching full match")
            // Use DoublesMatchRepository to get full match with players
            doublesMatchRepository.getById(matchId)
        } else {
            logger.error("❌ [RotationRepo] Failed to fetch match for rotation: status=${response.status}")
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
}

@Serializable
private data class RotationRaw(
    val id: String,
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("rotation_number") val rotationNumber: Int,
    @SerialName("created_at") val createdAt: String
) {
    fun toRotationResponse(match: DoublesMatchResponse?) = RotationResponse(
        id = id,
        dayGroupId = dayGroupId,
        rotationNumber = rotationNumber,
        createdAt = createdAt,
        match = match
    )
}

/**
 * Optimized data class for parsing nested Supabase response
 * Includes embedded match with players in a single query result
 */
@Serializable
private data class RotationWithMatchRaw(
    val id: String,
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("rotation_number") val rotationNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("doubles_matches") val doublesMatches: List<DoublesMatchWithPlayersRaw> = emptyList()
) {
    fun toRotationResponse(): RotationResponse {
        val match = doublesMatches.firstOrNull()?.toDoublesMatchResponse()
        return RotationResponse(
            id = id,
            dayGroupId = dayGroupId,
            rotationNumber = rotationNumber,
            createdAt = createdAt,
            match = match
        )
    }
}

@Serializable
private data class DoublesMatchWithPlayersRaw(
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
    @SerialName("forfeit_recorded_at") val forfeitRecordedAt: String? = null,
    // Nested player data from joins
    @SerialName("team1_player1") val team1Player1: LeaguePlayerResponse? = null,
    @SerialName("team1_player2") val team1Player2: LeaguePlayerResponse? = null,
    @SerialName("team2_player1") val team2Player1: LeaguePlayerResponse? = null,
    @SerialName("team2_player2") val team2Player2: LeaguePlayerResponse? = null
) {
    fun toDoublesMatchResponse() = DoublesMatchResponse(
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