package repositories.league

import config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.league.DayGroupResponse
import models.league.LeaguePlayerResponse

class DayGroupRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DayGroupRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

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
}

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
