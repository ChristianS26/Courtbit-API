package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import models.league.CreateMatchdayScheduleOverrideRequest
import models.league.MatchdayScheduleOverrideResponse
import models.league.UpdateMatchdayScheduleOverrideRequest

interface MatchdayScheduleOverridesRepository {
    suspend fun getBySeasonId(seasonId: String): List<MatchdayScheduleOverrideResponse>
    suspend fun getBySeasonAndMatchday(seasonId: String, matchdayNumber: Int): MatchdayScheduleOverrideResponse?
    suspend fun create(request: CreateMatchdayScheduleOverrideRequest): MatchdayScheduleOverrideResponse?
    suspend fun update(id: String, request: UpdateMatchdayScheduleOverrideRequest): Boolean
    suspend fun delete(id: String): Boolean
}

class MatchdayScheduleOverridesRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : MatchdayScheduleOverridesRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getBySeasonId(seasonId: String): List<MatchdayScheduleOverrideResponse> {
        val response = client.get("$apiUrl/matchday_schedule_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "matchday_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<MatchdayScheduleOverrideResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getBySeasonAndMatchday(seasonId: String, matchdayNumber: Int): MatchdayScheduleOverrideResponse? {
        val response = client.get("$apiUrl/matchday_schedule_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("season_id", "eq.$seasonId")
            parameter("matchday_number", "eq.$matchdayNumber")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<MatchdayScheduleOverrideResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun create(request: CreateMatchdayScheduleOverrideRequest): MatchdayScheduleOverrideResponse? {
        val response = client.post("$apiUrl/matchday_schedule_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateMatchdayScheduleOverrideRequest.serializer(), request))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<MatchdayScheduleOverrideResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun update(id: String, request: UpdateMatchdayScheduleOverrideRequest): Boolean {
        val response = client.patch("$apiUrl/matchday_schedule_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateMatchdayScheduleOverrideRequest.serializer(), request))
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val response = client.delete("$apiUrl/matchday_schedule_overrides") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return response.status.isSuccess()
    }
}
