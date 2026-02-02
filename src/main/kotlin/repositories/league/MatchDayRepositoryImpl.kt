package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.league.MatchDayResponse

class MatchDayRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : MatchDayRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<MatchDayResponse> {
        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("order", "match_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<MatchDayResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getByCategoryId(categoryId: String): List<MatchDayResponse> {
        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "match_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<MatchDayResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): MatchDayResponse? {
        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<MatchDayResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }
}
