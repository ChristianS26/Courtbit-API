package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import models.league.CreateSeasonScheduleDefaultsRequest
import models.league.SeasonScheduleDefaultsResponse
import models.league.UpdateSeasonScheduleDefaultsRequest

interface SeasonScheduleDefaultsRepository {
    suspend fun getBySeasonId(seasonId: String): SeasonScheduleDefaultsResponse?
    suspend fun create(request: CreateSeasonScheduleDefaultsRequest): SeasonScheduleDefaultsResponse?
    suspend fun update(seasonId: String, request: UpdateSeasonScheduleDefaultsRequest): Boolean
    suspend fun delete(seasonId: String): Boolean
}

class SeasonScheduleDefaultsRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : SeasonScheduleDefaultsRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getBySeasonId(seasonId: String): SeasonScheduleDefaultsResponse? {
        val response = client.get("$apiUrl/season_schedule_defaults") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("season_id", "eq.$seasonId")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<SeasonScheduleDefaultsResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun create(request: CreateSeasonScheduleDefaultsRequest): SeasonScheduleDefaultsResponse? {
        val response = client.post("$apiUrl/season_schedule_defaults") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateSeasonScheduleDefaultsRequest.serializer(), request))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<SeasonScheduleDefaultsResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun update(seasonId: String, request: UpdateSeasonScheduleDefaultsRequest): Boolean {
        val response = client.patch("$apiUrl/season_schedule_defaults") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateSeasonScheduleDefaultsRequest.serializer(), request))
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(seasonId: String): Boolean {
        val response = client.delete("$apiUrl/season_schedule_defaults") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
        }

        return response.status.isSuccess()
    }
}
