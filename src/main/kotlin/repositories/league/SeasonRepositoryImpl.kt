package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.league.CreateSeasonRequest
import models.league.SeasonResponse
import models.league.UpdateSeasonRequest
import repositories.tournament.OrganizerInfo

class SeasonRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : SeasonRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<SeasonResponse> {
        val response = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,organizers!organizer_id(name)")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<SeasonRawResponse>>(bodyText)
            rawList.map { it.toSeasonResponse() }
        } else {
            println("❌ Error getAll seasons: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getByOrganizerId(organizerId: String): List<SeasonResponse> {
        val response = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,organizers!organizer_id(name)")
            parameter("organizer_id", "eq.$organizerId")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<SeasonRawResponse>>(bodyText)
            rawList.map { it.toSeasonResponse() }
        } else {
            println("❌ Error getByOrganizerId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): SeasonResponse? {
        val response = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            parameter("select", "*,organizers!organizer_id(name)")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<SeasonRawResponse>>(bodyText)
            rawList.firstOrNull()?.toSeasonResponse()
        } else {
            println("❌ Error getById: ${response.status}")
            null
        }
    }

    override suspend fun create(request: CreateSeasonRequest): SeasonResponse? {
        val url = "$apiUrl/seasons"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(request))
            // Include organizer data in the response
            parameter("select", "*,organizers!organizer_id(name)")
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }
        println("🛰️ Supabase POST $url -> ${status.value} ${status.description}\nBody: $bodyText")

        if (!status.isSuccess()) return null

        return bodyText.takeIf { it.isNotBlank() }?.let {
            // Decode as SeasonRawResponse to handle the joined organizer data
            val rawList = json.decodeFromString<List<SeasonRawResponse>>(it)
            rawList.firstOrNull()?.toSeasonResponse()
        }
    }

    override suspend fun update(id: String, request: UpdateSeasonRequest): Boolean {
        val response = client.patch("$apiUrl/seasons?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/seasons?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            val status = response.status
            val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }
            println("🗑️ Supabase DELETE $url -> ${status.value} ${status.description}")

            status.isSuccess()
        } catch (e: Exception) {
            println("🧨 Supabase DELETE exception for season $id: ${e.stackTraceToString()}")
            false
        }
    }

    override suspend fun getActiveByOrganizer(organizerId: String): SeasonResponse? {
        val response = client.get("$apiUrl/seasons") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,organizers!organizer_id(name)")
            parameter("organizer_id", "eq.$organizerId")
            parameter("is_active", "eq.true")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<SeasonRawResponse>>(bodyText)
            rawList.firstOrNull()?.toSeasonResponse()
        } else {
            null
        }
    }
}

@Serializable
data class SeasonRawResponse(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("registrations_open") val registrationsOpen: Boolean = false,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int = 2,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int = 4,
    @SerialName("organizer_id") val organizerId: String?,
    val organizers: OrganizerInfo?,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean = true,
    @SerialName("ranking_criteria") val rankingCriteria: List<String> = listOf("adjusted_points", "point_diff", "games_won"),
    @SerialName("max_points_per_game") val maxPointsPerGame: Int = 6,
    @SerialName("forfeit_winner_points") val forfeitWinnerPoints: Int = 15,
    @SerialName("forfeit_loser_points") val forfeitLoserPoints: Int = 12,
    @SerialName("requires_shirt_size") val requiresShirtSize: Boolean = false,
    @SerialName("requires_shirt_name") val requiresShirtName: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
) {
    fun toSeasonResponse() = SeasonResponse(
        id = id,
        name = name,
        startDate = startDate,
        endDate = endDate,
        isActive = isActive,
        registrationsOpen = registrationsOpen,
        playersDirectToFinal = playersDirectToFinal,
        playersInSemifinals = playersInSemifinals,
        organizerId = organizerId,
        organizerName = organizers?.name,
        allowPlayerScores = allowPlayerScores,
        rankingCriteria = rankingCriteria,
        maxPointsPerGame = maxPointsPerGame,
        forfeitWinnerPoints = forfeitWinnerPoints,
        forfeitLoserPoints = forfeitLoserPoints,
        requiresShirtSize = requiresShirtSize,
        requiresShirtName = requiresShirtName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
