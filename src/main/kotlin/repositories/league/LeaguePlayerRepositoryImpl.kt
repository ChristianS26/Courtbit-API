package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.UpdateLeaguePlayerRequest

class LeaguePlayerRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : LeaguePlayerRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<LeaguePlayerResponse> {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            println("❌ Error getAll league players: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getByCategoryId(categoryId: String): List<LeaguePlayerResponse> {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            println("❌ Error getByCategoryId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeaguePlayerResponse? {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            list.firstOrNull()
        } else {
            println("❌ Error getById: ${response.status}")
            null
        }
    }

    override suspend fun create(request: CreateLeaguePlayerRequest): LeaguePlayerResponse? {
        val url = "$apiUrl/league_players"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(request))
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }

        if (!status.isSuccess()) return null

        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<LeaguePlayerResponse>>(it).firstOrNull()
        }
    }

    override suspend fun update(id: String, request: UpdateLeaguePlayerRequest): Boolean {
        val response = client.patch("$apiUrl/league_players?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/league_players?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            println("🧨 Supabase DELETE exception for league player $id: ${e.stackTraceToString()}")
            false
        }
    }
}
