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
import repositories.league.LeagueCategoryRepository
import repositories.league.SeasonRepository

class RankingService(
    private val client: HttpClient,
    private val config: SupabaseConfig,
    private val json: Json,
    private val categoryRepository: LeagueCategoryRepository,
    private val seasonRepository: SeasonRepository
) {
    companion object {
        // Default ranking criteria if none specified
        private val DEFAULT_RANKING_CRITERIA = listOf("adjusted_points", "point_diff", "games_won")
    }

    suspend fun getRankings(categoryId: String): List<PlayerStandingResponse> {
        // 1. Fetch raw rankings from database
        val rawRankings = fetchRawRankings(categoryId)
        if (rawRankings.isEmpty()) return emptyList()

        // 2. Get ranking criteria from the season
        val rankingCriteria = getSeasonRankingCriteria(categoryId)

        // 3. Sort by the criteria and reassign ranks
        return sortAndRank(rawRankings, rankingCriteria)
    }

    /**
     * Fetches raw rankings from the database function.
     * The database returns results with stats calculated, but we re-sort based on season criteria.
     */
    private suspend fun fetchRawRankings(categoryId: String): List<PlayerStandingResponse> {
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

    /**
     * Gets the ranking criteria from the season configuration.
     */
    private suspend fun getSeasonRankingCriteria(categoryId: String): List<String> {
        // Get category to find season ID
        val category = categoryRepository.getById(categoryId) ?: return DEFAULT_RANKING_CRITERIA

        // Get season's ranking criteria
        val season = seasonRepository.getById(category.seasonId)
        if (season != null && season.rankingCriteria.isNotEmpty()) {
            return season.rankingCriteria
        }

        return DEFAULT_RANKING_CRITERIA
    }

    /**
     * Sorts the rankings based on the specified criteria and reassigns rank values.
     * Also populates primaryCriterion and primaryValue for frontend UX.
     */
    private fun sortAndRank(
        rankings: List<PlayerStandingResponse>,
        criteria: List<String>
    ): List<PlayerStandingResponse> {
        // Create a comparator based on the criteria order
        val comparator = createComparator(criteria)

        // Get the primary (first) criterion
        val primaryCriterion = criteria.firstOrNull() ?: "adjusted_points"

        // Sort and reassign ranks with primary criterion info
        return rankings
            .sortedWith(comparator)
            .mapIndexed { index, standing ->
                standing.copy(
                    rank = index + 1,
                    primaryCriterion = primaryCriterion,
                    primaryValue = getPrimaryValue(standing, primaryCriterion)
                )
            }
    }

    /**
     * Gets the value for a specific criterion from a player standing.
     */
    private fun getPrimaryValue(standing: PlayerStandingResponse, criterion: String): Int {
        return when (criterion) {
            "adjusted_points" -> standing.adjustedPointsFor
            "point_diff" -> standing.adjustedDiff
            "games_won" -> standing.gamesWon
            "games_lost" -> standing.gamesLost
            "points_for" -> standing.pointsFor
            "points_against" -> standing.pointsAgainst
            else -> standing.adjustedPointsFor // Default fallback
        }
    }

    /**
     * Creates a comparator that sorts by the given criteria in order.
     * Each criterion adds a level of comparison.
     */
    private fun createComparator(criteria: List<String>): Comparator<PlayerStandingResponse> {
        var comparator: Comparator<PlayerStandingResponse>? = null

        for (criterion in criteria) {
            val criterionComparator = getCriterionComparator(criterion)
            comparator = if (comparator == null) {
                criterionComparator
            } else {
                comparator.then(criterionComparator)
            }
        }

        // Add player name as final tiebreaker (alphabetical ascending)
        val nameComparator = compareBy<PlayerStandingResponse> { it.playerName }
        return comparator?.then(nameComparator) ?: nameComparator
    }

    /**
     * Returns a comparator for a single ranking criterion.
     */
    private fun getCriterionComparator(criterion: String): Comparator<PlayerStandingResponse> {
        return when (criterion) {
            "adjusted_points" -> compareByDescending { it.adjustedPointsFor }
            "point_diff" -> compareByDescending { it.adjustedDiff }
            "games_won" -> compareByDescending { it.gamesWon }
            "games_lost" -> compareBy { it.gamesLost } // Lower is better
            "points_for" -> compareByDescending { it.pointsFor }
            "points_against" -> compareBy { it.pointsAgainst } // Lower is better
            else -> {
                // Unknown criterion - treat as no-op
                Comparator { _, _ -> 0 }
            }
        }
    }
}
