package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.league.LeagueCategoryResponse
import repositories.league.LeagueCategoryRepository
import repositories.league.LeaguePlayerRepository
import repositories.league.MatchDayRepository

class LeagueCategoryService(
    private val repository: LeagueCategoryRepository,
    private val playerRepository: LeaguePlayerRepository,
    private val matchDayRepository: MatchDayRepository,
    private val client: HttpClient,
    private val config: SupabaseConfig
) {
    suspend fun getCategoryWithStatus(id: String): LeagueCategoryResponse? {
        val category = repository.getById(id) ?: return null

        // Enrich with player count
        val playerCount = playerRepository.getByCategoryId(id).size

        // Check if calendar exists
        val hasCalendar = matchDayRepository.getByCategoryId(id).isNotEmpty()

        return category.copy(
            playerCount = playerCount,
            hasCalendar = hasCalendar
        )
    }

    suspend fun generateCalendar(categoryId: String): Result<String> {
        // Validate player count
        val players = playerRepository.getByCategoryId(categoryId)
        if (players.size != 16) {
            return Result.failure(
                IllegalStateException("Category must have exactly 16 players (has ${players.size})")
            )
        }

        // Call RPC
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        val response = client.post("${config.apiUrl}/rpc/generate_league_calendar") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        return if (response.status.isSuccess()) {
            val result = response.bodyAsText()
            Result.success(result)
        } else {
            Result.failure(IllegalStateException("Calendar generation failed: ${response.status}"))
        }
    }
}
