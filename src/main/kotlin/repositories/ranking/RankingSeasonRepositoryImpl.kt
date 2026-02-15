package repositories.ranking

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.ranking.RankingSeasonResponse

class RankingSeasonRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : RankingSeasonRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val table = "ranking_seasons"

    override suspend fun getAllByOrganizer(organizerId: String): List<RankingSeasonResponse> {
        val response = client.get("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("organizer_id", "eq.$organizerId")
            parameter("order", "created_at.desc")
        }
        return if (response.status.isSuccess()) {
            json.decodeFromString(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun getActiveByOrganizer(organizerId: String): RankingSeasonResponse? {
        val response = client.get("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("organizer_id", "eq.$organizerId")
            parameter("status", "eq.active")
            parameter("limit", "1")
        }
        return if (response.status.isSuccess()) {
            val list: List<RankingSeasonResponse> = json.decodeFromString(response.bodyAsText())
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun getById(id: String): RankingSeasonResponse? {
        val response = client.get("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            header("Accept", "application/vnd.pgrst.object+json")
        }
        return if (response.status.isSuccess()) {
            json.decodeFromString(response.bodyAsText())
        } else {
            null
        }
    }

    override suspend fun create(body: String): RankingSeasonResponse {
        val response = client.post("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            header("Accept", "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Error creating ranking season: ${response.status} ${response.bodyAsText()}")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    override suspend fun update(id: String, body: String): RankingSeasonResponse? {
        val response = client.patch("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            header("Accept", "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$id")
            setBody(body)
        }
        return if (response.status.isSuccess()) {
            json.decodeFromString(response.bodyAsText())
        } else {
            null
        }
    }

    override suspend fun delete(id: String): Boolean {
        val response = client.delete("$apiUrl/$table") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }
        return response.status.isSuccess()
    }
}
