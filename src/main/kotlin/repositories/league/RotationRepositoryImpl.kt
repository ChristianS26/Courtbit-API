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

    override suspend fun getByDayGroupId(dayGroupId: String): List<RotationResponse> {
        logger.info("🔍 [RotationRepo] getByDayGroupId($dayGroupId) - URL: $apiUrl/rotations")

        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("day_group_id", "eq.$dayGroupId")
            parameter("order", "rotation_number.asc")
        }

        val bodyText = response.bodyAsText()
        logger.info("🔍 [RotationRepo] Response status: ${response.status}, body: $bodyText")

        return if (response.status.isSuccess()) {
            val rawList = json.decodeFromString<List<RotationRaw>>(bodyText)
            logger.info("🔍 [RotationRepo] Found ${rawList.size} rotations for dayGroupId=$dayGroupId")

            // Enrich with match
            rawList.map { raw ->
                val match = fetchMatchByRotationId(raw.id)
                raw.toRotationResponse(match)
            }
        } else {
            logger.error("❌ [RotationRepo] Failed to fetch rotations: status=${response.status}, body=$bodyText")
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