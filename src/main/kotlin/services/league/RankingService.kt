package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.league.PlayerStandingResponse

class RankingService(
    private val client: HttpClient,
    private val config: SupabaseConfig,
    private val json: Json
) {
    suspend fun getRankings(categoryId: String): List<PlayerStandingResponse> {
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        val response = client.post("${config.apiUrl}/rpc/calculate_league_rankings") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<PlayerStandingResponse>>(bodyText)
        } else {
            emptyList()
        }
    }
}
