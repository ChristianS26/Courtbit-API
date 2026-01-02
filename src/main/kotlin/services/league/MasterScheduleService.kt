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
    private val overridesRepository: MatchdayScheduleOverridesRepository
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
