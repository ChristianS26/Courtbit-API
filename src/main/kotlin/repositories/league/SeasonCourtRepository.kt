package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import models.league.CreateCourtInternal
import models.league.SeasonCourtResponse
import models.league.UpdateSeasonCourtRequest
import org.slf4j.LoggerFactory

interface SeasonCourtRepository {
    suspend fun getBySeasonId(seasonId: String, includeInactive: Boolean = false): List<SeasonCourtResponse>
    suspend fun getById(id: String): SeasonCourtResponse?
    suspend fun create(seasonId: String, name: String): SeasonCourtResponse?
    suspend fun bulkCreate(seasonId: String, count: Int): List<SeasonCourtResponse>
    suspend fun update(id: String, request: UpdateSeasonCourtRequest): Boolean
    suspend fun softDelete(id: String): Boolean
    suspend fun reactivate(id: String): Boolean
    suspend fun copyFromSeason(sourceSeasonId: String, targetSeasonId: String): List<SeasonCourtResponse>
    suspend fun getNextCourtNumber(seasonId: String): Int
}

class SeasonCourtRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : SeasonCourtRepository {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(SeasonCourtRepositoryImpl::class.java)

    override suspend fun getBySeasonId(seasonId: String, includeInactive: Boolean): List<SeasonCourtResponse> {
        val response = client.get("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("season_id", "eq.$seasonId")
            if (!includeInactive) {
                parameter("is_active", "eq.true")
            }
            parameter("order", "court_number.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<SeasonCourtResponse>>(bodyText)
        } else {
            logger.error("Failed to get courts for season $seasonId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): SeasonCourtResponse? {
        val response = client.get("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$id")
            parameter("limit", "1")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<SeasonCourtResponse>>(bodyText)
            list.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun create(seasonId: String, name: String): SeasonCourtResponse? {
        val nextNumber = getNextCourtNumber(seasonId)
        val request = CreateCourtInternal(
            seasonId = seasonId,
            courtNumber = nextNumber,
            name = name,
            isActive = true
        )

        val response = client.post("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCourtInternal.serializer(), request))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<SeasonCourtResponse>>(bodyText)
            list.firstOrNull()
        } else {
            val errorBody = response.bodyAsText()
            logger.error("Failed to create court: ${response.status} - $errorBody")
            null
        }
    }

    override suspend fun bulkCreate(seasonId: String, count: Int): List<SeasonCourtResponse> {
        if (count <= 0) return emptyList()

        val startNumber = getNextCourtNumber(seasonId)
        val courts = (0 until count).map { i ->
            CreateCourtInternal(
                seasonId = seasonId,
                courtNumber = startNumber + i,
                name = (startNumber + i).toString(),
                isActive = true
            )
        }

        val response = client.post("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CreateCourtInternal.serializer()), courts))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<SeasonCourtResponse>>(bodyText)
        } else {
            val errorBody = response.bodyAsText()
            logger.error("Failed to bulk create courts: ${response.status} - $errorBody")
            emptyList()
        }
    }

    override suspend fun update(id: String, request: UpdateSeasonCourtRequest): Boolean {
        val response = client.patch("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateSeasonCourtRequest.serializer(), request))
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Failed to update court $id: ${response.status} - $errorBody")
        }

        return response.status.isSuccess()
    }

    override suspend fun softDelete(id: String): Boolean {
        return update(id, UpdateSeasonCourtRequest(isActive = false))
    }

    override suspend fun reactivate(id: String): Boolean {
        return update(id, UpdateSeasonCourtRequest(isActive = true))
    }

    override suspend fun copyFromSeason(sourceSeasonId: String, targetSeasonId: String): List<SeasonCourtResponse> {
        // Get courts from source season (including inactive for complete copy)
        val sourceCourts = getBySeasonId(sourceSeasonId, includeInactive = true)
        if (sourceCourts.isEmpty()) {
            logger.warn("No courts found in source season $sourceSeasonId")
            return emptyList()
        }

        // Create courts in target season with same names and numbers
        val newCourts = sourceCourts.map { source ->
            CreateCourtInternal(
                seasonId = targetSeasonId,
                courtNumber = source.courtNumber,
                name = source.name,
                isActive = source.isActive
            )
        }

        val response = client.post("$apiUrl/season_courts") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CreateCourtInternal.serializer()), newCourts))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<SeasonCourtResponse>>(bodyText)
        } else {
            val errorBody = response.bodyAsText()
            logger.error("Failed to copy courts: ${response.status} - $errorBody")
            emptyList()
        }
    }

    override suspend fun getNextCourtNumber(seasonId: String): Int {
        val courts = getBySeasonId(seasonId, includeInactive = true)
        return if (courts.isEmpty()) 1 else courts.maxOf { it.courtNumber } + 1
    }
}
