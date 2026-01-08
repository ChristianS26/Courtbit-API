package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.league.*
import repositories.league.LeagueCategoryRepository
import repositories.league.MatchdayScheduleOverridesRepository
import repositories.league.SeasonScheduleDefaultsRepository

/**
 * Service to manage master schedule - combines defaults, overrides, and day group assignments
 */
class MasterScheduleService(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val defaultsRepository: SeasonScheduleDefaultsRepository,
    private val overridesRepository: MatchdayScheduleOverridesRepository,
    private val categoryRepository: LeagueCategoryRepository
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    /**
     * Get complete master schedule for a season's category
     * Includes: schedule defaults, matchday overrides, and all match days with groups
     */
    suspend fun getMasterSchedule(seasonId: String, categoryId: String): MasterScheduleResponse? {
        // Fetch schedule defaults
        val defaults = defaultsRepository.getBySeasonId(seasonId)

        // Fetch matchday overrides
        val overrides = overridesRepository.getBySeasonId(seasonId)

        // Fetch all match days for the category with day groups
        val matchDays = fetchMatchDaysWithGroups(categoryId)

        return MasterScheduleResponse(
            seasonId = seasonId,
            defaults = defaults,
            matchdayOverrides = overrides,
            matchDays = matchDays
        )
    }

    /**
     * Get bulk schedule data for a season and matchday number
     * Returns all categories with their schedule data in a single response
     * This is the optimized endpoint that replaces multiple API calls
     */
    suspend fun getBulkSchedule(seasonId: String, matchdayNumber: Int): BulkScheduleResponse {
        // Fetch all data in parallel-ish manner (Supabase handles optimization)
        val categories = categoryRepository.getBySeasonId(seasonId)
        val defaults = defaultsRepository.getBySeasonId(seasonId)
        val overrides = overridesRepository.getBySeasonId(seasonId)

        // Fetch all players for this season (to avoid N+1 queries)
        val allPlayers = fetchAllPlayersForSeason(seasonId)
        val playerMap = allPlayers.associateBy { it.id }

        // Fetch matchdays with day groups for all categories in a single query
        val categorySchedules = categories.map { category ->
            val matchDayWithGroups = fetchMatchDayWithGroupsEnriched(
                categoryId = category.id,
                matchNumber = matchdayNumber,
                playerMap = playerMap
            )
            CategoryScheduleData(
                categoryId = category.id,
                matchDay = matchDayWithGroups
            )
        }

        return BulkScheduleResponse(
            seasonId = seasonId,
            matchdayNumber = matchdayNumber,
            categories = categories,
            defaults = defaults,
            matchdayOverrides = overrides,
            categorySchedules = categorySchedules
        )
    }

    /**
     * Fetch all players for a season (for bulk loading)
     */
    private suspend fun fetchAllPlayersForSeason(seasonId: String): List<LeaguePlayerResponse> {
        // First get all category IDs for this season
        val categories = categoryRepository.getBySeasonId(seasonId)
        val categoryIds = categories.map { it.id }

        if (categoryIds.isEmpty()) return emptyList()

        // Fetch all players from these categories
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("category_id", "in.(${categoryIds.joinToString(",")})")
            parameter("is_waiting_list", "eq.false")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    /**
     * Fetch a specific matchday with day groups, enriched with player data and rotation counts
     */
    private suspend fun fetchMatchDayWithGroupsEnriched(
        categoryId: String,
        matchNumber: Int,
        playerMap: Map<String, LeaguePlayerResponse>
    ): MatchDayWithGroupsEnriched? {
        // Fetch matchday with embedded day_groups and rotations
        val selectQuery = "*,day_groups(*,rotations(id,doubles_matches(score_team1,score_team2)))"

        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectQuery)
            parameter("category_id", "eq.$categoryId")
            parameter("match_number", "eq.$matchNumber")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<MatchDayWithGroupsEnrichedRaw>>(bodyText)
            rawList.firstOrNull()?.toEnriched(playerMap)
        } else {
            null
        }
    }

    private suspend fun fetchMatchDaysWithGroups(categoryId: String): List<MatchDayWithGroupsResponse> {
        // Use embedded resources to fetch match days with day groups
        val selectQuery = "*,day_groups(*)"

        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectQuery)
            parameter("category_id", "eq.$categoryId")
            parameter("order", "match_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<MatchDayWithGroupsRaw>>(bodyText)
            rawList.map { it.toResponse() }
        } else {
            emptyList()
        }
    }
}

// Raw response for deserialization
@Serializable
private data class MatchDayWithGroupsRaw(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<DayGroupScheduleInfoRaw>
) {
    fun toResponse() = MatchDayWithGroupsResponse(
        id = id,
        categoryId = categoryId,
        matchNumber = matchNumber,
        dayGroups = dayGroups.map { it.toResponse() },
        createdAt = createdAt
    )
}

@Serializable
private data class DayGroupScheduleInfoRaw(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String
) {
    fun toResponse() = DayGroupScheduleInfo(
        id = id,
        matchDayId = matchDayId,
        groupNumber = groupNumber,
        playerIds = playerIds,
        matchDate = matchDate,
        timeSlot = timeSlot,
        courtIndex = courtIndex,
        createdAt = createdAt
    )
}

// Raw classes for enriched bulk schedule query
@Serializable
private data class MatchDayWithGroupsEnrichedRaw(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<DayGroupEnrichedRaw>
) {
    fun toEnriched(playerMap: Map<String, LeaguePlayerResponse>) = MatchDayWithGroupsEnriched(
        id = id,
        categoryId = categoryId,
        matchNumber = matchNumber,
        dayGroups = dayGroups.map { it.toEnriched(playerMap) },
        createdAt = createdAt
    )
}

@Serializable
private data class DayGroupEnrichedRaw(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String,
    val rotations: List<RotationWithMatchRaw> = emptyList()
) {
    fun toEnriched(playerMap: Map<String, LeaguePlayerResponse>): DayGroupEnriched {
        // Get players from the map
        val players = playerIds.mapNotNull { playerMap[it] }

        // Count completed rotations (where both scores are non-null)
        val completedRotations = rotations.count { rotation ->
            val match = rotation.doublesMatch
            match != null && match.scoreTeam1 != null && match.scoreTeam2 != null
        }

        return DayGroupEnriched(
            id = id,
            matchDayId = matchDayId,
            groupNumber = groupNumber,
            playerIds = playerIds,
            matchDate = matchDate,
            timeSlot = timeSlot,
            courtIndex = courtIndex,
            createdAt = createdAt,
            players = players,
            completedRotations = completedRotations
        )
    }
}

@Serializable
private data class RotationWithMatchRaw(
    val id: String,
    @SerialName("doubles_matches") val doublesMatch: DoublesMatchScoreRaw? = null
)

@Serializable
private data class DoublesMatchScoreRaw(
    @SerialName("score_team1") val scoreTeam1: Int?,
    @SerialName("score_team2") val scoreTeam2: Int?
)
