package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.league.CreateLeagueCategoryRequest
import models.league.LeagueCategoryResponse
import models.league.UpdateLeagueCategoryRequest

class LeagueCategoryRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : LeagueCategoryRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<LeagueCategoryResponse> {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("order", "created_at.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeagueCategoryResponse>>(bodyText)
        } else {
            println("❌ Error getAll league categories: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getBySeasonId(seasonId: String): List<LeagueCategoryResponse> {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "created_at.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeagueCategoryResponse>>(bodyText)
        } else {
            println("❌ Error getBySeasonId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeagueCategoryResponse? {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeagueCategoryResponse>>(bodyText)
            list.firstOrNull()
        } else {
            println("❌ Error getById: ${response.status}")
            null
        }
    }

    override suspend fun create(request: CreateLeagueCategoryRequest): LeagueCategoryResponse? {
        val url = "$apiUrl/league_categories"
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
            json.decodeFromString<List<LeagueCategoryResponse>>(it).firstOrNull()
        }
    }

    override suspend fun update(id: String, request: UpdateLeagueCategoryRequest): Boolean {
        val response = client.patch("$apiUrl/league_categories?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/league_categories?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            println("🧨 Supabase DELETE exception for league category $id: ${e.stackTraceToString()}")
            false
        }
    }
}
