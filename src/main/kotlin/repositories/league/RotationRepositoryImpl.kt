package repositories.league

import config.SupabaseConfig
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
    private val config: SupabaseConfig
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
        val response = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("rotation_id", "eq.$rotationId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<DoublesMatchRaw>>(bodyText)
            val raw = rawList.firstOrNull() ?: return null

            // Enrich with players
            val team1Player1 = raw.team1Player1Id?.let { fetchPlayerById(it) }
            val team1Player2 = raw.team1Player2Id?.let { fetchPlayerById(it) }
            val team2Player1 = raw.team2Player1Id?.let { fetchPlayerById(it) }
            val team2Player2 = raw.team2Player2Id?.let { fetchPlayerById(it) }

            raw.toDoublesMatchResponse(team1Player1, team1Player2, team2Player1, team2Player2)
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
    @SerialName("updated_at") val updatedAt: String
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
        team2Player2 = team2Player2
    )
}
