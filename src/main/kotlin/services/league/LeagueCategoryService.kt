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

        // Get all players and separate by waiting list status
        val allPlayers = playerRepository.getByCategoryId(id)
        val activePlayers = allPlayers.filter { !it.isWaitingList }
        val waitingListPlayers = allPlayers.filter { it.isWaitingList }

        // Check if calendar exists
        val hasCalendar = matchDayRepository.getByCategoryId(id).isNotEmpty()

        return category.copy(
            playerCount = activePlayers.size,
            waitingListCount = waitingListPlayers.size,
            hasCalendar = hasCalendar
        )
    }

    /**
     * Generate calendar for a category with optional player sorting.
     * @param categoryId The category ID
     * @param sortOrder How to sort players: "alphabetical" (default), "random", or "registration"
     */
    suspend fun generateCalendar(categoryId: String, sortOrder: String = "alphabetical"): Result<String> {
        // Validate player count - only count active players (not on waiting list)
        val allPlayers = playerRepository.getByCategoryId(categoryId)
        val activePlayers = allPlayers.filter { !it.isWaitingList }
        if (activePlayers.size != 16 && activePlayers.size != 20) {
            return Result.failure(
                IllegalStateException("Category must have exactly 16 or 20 active players (has ${activePlayers.size})")
            )
        }

        // Validate sort order
        val validSortOrders = listOf("alphabetical", "random", "registration")
        val normalizedSortOrder = if (sortOrder in validSortOrders) sortOrder else "alphabetical"

        // Call RPC with sort order
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
            put("p_sort_order", normalizedSortOrder)
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
