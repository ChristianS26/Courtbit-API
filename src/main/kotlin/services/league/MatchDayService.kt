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

/**
 * Optimized service that fetches complete match day data with all nested relationships
 * in a single Supabase query using embedded resources
 */
class MatchDayService(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    /**
     * Fetches complete match day with all nested data in ONE optimized query
     * Returns: MatchDay -> DayGroups -> Players, Rotations -> Matches -> Players
     *
     * Previously: 13+ sequential requests + 60+ database queries
     * Now: 1 request with 3-4 database joins
     */
    suspend fun getCompleteMatchDay(matchDayId: String): CompleteMatchDayResponse? {
        // Use Supabase embedded resources to fetch everything in one query
        val selectQuery = """
            *,
            day_groups(
                *,
                rotations(
                    *,
                    doubles_matches(
                        *,
                        team1_player1:league_players!team1_player1_id(*),
                        team1_player2:league_players!team1_player2_id(*),
                        team2_player1:league_players!team2_player1_id(*),
                        team2_player2:league_players!team2_player2_id(*)
                    )
                )
            )
        """.trimIndent().replace("\n", "")

        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectQuery)
            parameter("id", "eq.$matchDayId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<CompleteMatchDayRaw>>(bodyText)
            list.firstOrNull()?.toResponse()
        } else {
            println("❌ Error fetching complete match day: ${response.status}")
            println("Body: ${response.bodyAsText()}")
            null
        }
    }
}

// Raw response models for deserialization
@Serializable
private data class CompleteMatchDayRaw(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<DayGroupNested>
) {
    fun toResponse() = CompleteMatchDayResponse(
        id = id,
        categoryId = categoryId,
        matchNumber = matchNumber,
        createdAt = createdAt,
        dayGroups = dayGroups.map { it.toResponse() }
    )
}

@Serializable
private data class DayGroupNested(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String,
    val rotations: List<RotationNested>
) {
    fun toResponse() = DayGroupWithRotationsResponse(
        id = id,
        matchDayId = matchDayId,
        groupNumber = groupNumber,
        playerIds = playerIds,
        matchDate = matchDate,
        timeSlot = timeSlot,
        courtIndex = courtIndex,
        createdAt = createdAt,
        rotations = rotations.map { it.toResponse() }
    )
}

@Serializable
private data class RotationNested(
    val id: String,
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("rotation_number") val rotationNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("doubles_matches") val doublesMatches: List<DoublesMatchNested>?
) {
    fun toResponse() = RotationWithMatchResponse(
        id = id,
        dayGroupId = dayGroupId,
        rotationNumber = rotationNumber,
        createdAt = createdAt,
        match = doublesMatches?.firstOrNull()?.toResponse()
    )
}

@Serializable
private data class DoublesMatchNested(
    val id: String,
    @SerialName("rotation_id") val rotationId: String,
    @SerialName("team1_player1_id") val team1Player1Id: String?,
    @SerialName("team1_player2_id") val team1Player2Id: String?,
    @SerialName("team2_player1_id") val team2Player1Id: String?,
    @SerialName("team2_player2_id") val team2Player2Id: String?,
    @SerialName("score_team1") val scoreTeam1: Int?,
    @SerialName("score_team2") val scoreTeam2: Int?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("team1_player1") val team1Player1: List<LeaguePlayerResponse>?,
    @SerialName("team1_player2") val team1Player2: List<LeaguePlayerResponse>?,
    @SerialName("team2_player1") val team2Player1: List<LeaguePlayerResponse>?,
    @SerialName("team2_player2") val team2Player2: List<LeaguePlayerResponse>?
) {
    fun toResponse() = DoublesMatchResponse(
        id = id,
        rotationId = rotationId,
        team1Player1Id = team1Player1Id,
        team1Player2Id = team1Player2Id,
        team2Player1Id = team2Player1Id,
        team2Player2Id = team2Player2Id,
        scoreTeam1 = scoreTeam1,
        scoreTeam2 = scoreTeam2,
        createdAt = createdAt,
        updatedAt = updatedAt,
        team1Player1 = team1Player1?.firstOrNull(),
        team1Player2 = team1Player2?.firstOrNull(),
        team2Player1 = team2Player1?.firstOrNull(),
        team2Player2 = team2Player2?.firstOrNull()
    )
}

// Response models
@Serializable
data class CompleteMatchDayResponse(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<DayGroupWithRotationsResponse>
)

@Serializable
data class DayGroupWithRotationsResponse(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String,
    val rotations: List<RotationWithMatchResponse>
)

@Serializable
data class RotationWithMatchResponse(
    val id: String,
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("rotation_number") val rotationNumber: Int,
    @SerialName("created_at") val createdAt: String,
    val match: DoublesMatchResponse?
)
