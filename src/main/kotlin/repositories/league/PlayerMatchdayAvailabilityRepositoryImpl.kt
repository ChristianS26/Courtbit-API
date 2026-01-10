package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.league.PlayerMatchdayAvailabilityResponse

class PlayerMatchdayAvailabilityRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : PlayerMatchdayAvailabilityRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getBySeasonId(seasonId: String): List<PlayerMatchdayAvailabilityResponse> {
        val response = client.get("$apiUrl/player_matchday_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerMatchdayAvailabilityResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getBySeasonAndMatchday(
        seasonId: String,
        matchdayNumber: Int
    ): List<PlayerMatchdayAvailabilityResponse> {
        val response = client.get("$apiUrl/player_matchday_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("matchday_number", "eq.$matchdayNumber")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerMatchdayAvailabilityResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getByPlayerId(playerId: String): List<PlayerMatchdayAvailabilityResponse> {
        val response = client.get("$apiUrl/player_matchday_availability") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("player_id", "eq.$playerId")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerMatchdayAvailabilityResponse>>(bodyText)
        } else {
            emptyList()
        }
    }
}
