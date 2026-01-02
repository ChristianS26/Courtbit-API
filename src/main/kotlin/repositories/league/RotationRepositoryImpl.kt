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

class RotationRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val doublesMatchRepository: DoublesMatchRepository
) : RotationRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getByDayGroupId(dayGroupId: String): List<RotationResponse> {
        val response = client.get("$apiUrl/rotations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("day_group_id", "eq.$dayGroupId")
            parameter("order", "rotation_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<RotationRaw>>(bodyText)

            // Enrich with match
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
        // Query by rotation_id
        val response = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("rotation_id", "eq.$rotationId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val idList = json.decodeFromString<List<Map<String, String>>>(bodyText)
            val matchId = idList.firstOrNull()?.get("id") ?: return null

            // Use DoublesMatchRepository to get full match with players
            doublesMatchRepository.getById(matchId)
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