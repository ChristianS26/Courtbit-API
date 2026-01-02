package repositories.league

import config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.league.UpdateMatchScoreRequest

class DoublesMatchRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DoublesMatchRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun updateScore(matchId: String, request: UpdateMatchScoreRequest): Boolean {
        val payload = buildJsonObject {
            put("score_team1", request.scoreTeam1)
            put("score_team2", request.scoreTeam2)
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
