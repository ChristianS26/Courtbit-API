package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import models.league.CategoryPlayoffConfigResponse
import models.league.CreateLeagueCategoryRequest
import models.league.LeagueCategoryResponse
import models.league.UpdateCategoryPlayoffConfigRequest
import models.league.UpdateLeagueCategoryRequest

class LeagueCategoryRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : LeagueCategoryRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    // Select clause that joins the global categories table to get the name
    private val selectWithCategoryName = "*,categories(name)"

    /**
     * Parse raw JSON array from Supabase (with nested categories join) into LeagueCategoryResponse list.
     * Extracts the nested categories.name into globalCategoryName.
     */
    private fun parseWithCategoryName(bodyText: String): List<LeagueCategoryResponse> {
        val rawList = json.decodeFromString<List<JsonObject>>(bodyText)
        return rawList.map { obj ->
            val categoriesElement = obj["categories"]
            val categoryName = if (categoriesElement is JsonObject) {
                categoriesElement["name"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
            val base = json.decodeFromJsonElement(LeagueCategoryResponse.serializer(), obj)
            base.copy(globalCategoryName = categoryName)
        }
    }

    override suspend fun getAll(): List<LeagueCategoryResponse> {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithCategoryName)
            parameter("order", "created_at.desc")
        }

        return if (response.status.isSuccess()) {
            parseWithCategoryName(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun getBySeasonId(seasonId: String): List<LeagueCategoryResponse> {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithCategoryName)
            parameter("season_id", "eq.$seasonId")
            parameter("order", "created_at.desc")
        }

        return if (response.status.isSuccess()) {
            parseWithCategoryName(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeagueCategoryResponse? {
        val response = client.get("$apiUrl/league_categories") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithCategoryName)
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            parseWithCategoryName(response.bodyAsText()).firstOrNull()
        } else {
            null
        }
    }

    override suspend fun create(request: CreateLeagueCategoryRequest): LeagueCategoryResponse? {
        val url = "$apiUrl/league_categories"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(request))
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }

        if (!status.isSuccess()) return null

        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<LeagueCategoryResponse>>(it).firstOrNull()
        }
    }

    override suspend fun update(id: String, request: UpdateLeagueCategoryRequest): Boolean {
        val response = client.patch("$apiUrl/league_categories?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/league_categories?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    // MARK: - Max Players Configuration

    override suspend fun updateMaxPlayers(categoryId: String, maxPlayers: Int): Boolean {
        // Validate maxPlayers is 16 or 20
        if (maxPlayers != 16 && maxPlayers != 20) {
            return false
        }

        // Update the category's max_players
        val updateResponse = client.patch("$apiUrl/league_categories?id=eq.$categoryId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject {
                put("max_players", kotlinx.serialization.json.JsonPrimitive(maxPlayers))
            })
        }

        if (!updateResponse.status.isSuccess()) {
            return false
        }

        // Recalculate waiting list status for all players in this category
        // Get all players ordered by creation date (first registered = higher priority)
        val playersResponse = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "created_at.asc")
        }

        if (!playersResponse.status.isSuccess()) {
            return false
        }

        val players = json.decodeFromString<List<models.league.LeaguePlayerResponse>>(playersResponse.bodyAsText())

        // Update each player's waiting list status based on their position
        var activeCount = 0
        for (player in players) {
            val shouldBeWaitingList = activeCount >= maxPlayers

            // Only update if status needs to change
            if (player.isWaitingList != shouldBeWaitingList) {
                val playerUpdateResponse = client.patch("$apiUrl/league_players?id=eq.${player.id}") {
                    header("apikey", apiKey)
                    header("Authorization", "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(kotlinx.serialization.json.buildJsonObject {
                        put("is_waiting_list", kotlinx.serialization.json.JsonPrimitive(shouldBeWaitingList))
                    })
                }

                if (!playerUpdateResponse.status.isSuccess()) {
                }
            }

            if (!shouldBeWaitingList) {
                activeCount++
            }
        }

        return true
    }

    // MARK: - Playoff Configuration

    override suspend fun getEffectivePlayoffConfig(categoryId: String): CategoryPlayoffConfigResponse? {
        // Use the view that calculates effective config
        val response = client.get("$apiUrl/category_playoff_config") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("category_id", "eq.$categoryId")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<CategoryPlayoffConfigViewRow>>(bodyText)
            list.firstOrNull()?.let {
                CategoryPlayoffConfigResponse(
                    categoryId = it.categoryId,
                    playersDirectToFinal = it.effectiveDirectToFinal,
                    playersInSemifinals = it.effectiveInSemifinals,
                    configSource = it.configSource
                )
            }
        } else {
            null
        }
    }

    override suspend fun updatePlayoffConfig(
        categoryId: String,
        request: UpdateCategoryPlayoffConfigRequest
    ): Boolean {
        val response = client.patch("$apiUrl/league_categories?id=eq.$categoryId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun clearPlayoffConfig(categoryId: String): Boolean {
        // Set both fields to null to revert to season defaults
        val response = client.patch("$apiUrl/league_categories?id=eq.$categoryId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ClearPlayoffConfigRequest())
        }

        return response.status.isSuccess()
    }

    // MARK: - Recommended Courts Configuration

    override suspend fun updateRecommendedCourts(categoryId: String, courts: List<Int>?): Boolean {
        val response = client.patch("$apiUrl/league_categories?id=eq.$categoryId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject {
                if (courts != null) {
                    put("recommended_courts", kotlinx.serialization.json.buildJsonArray {
                        courts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                } else {
                    put("recommended_courts", kotlinx.serialization.json.JsonNull)
                }
            })
        }

        return response.status.isSuccess()
    }
}

// Internal DTO for the view response
@Serializable
private data class CategoryPlayoffConfigViewRow(
    @kotlinx.serialization.SerialName("category_id") val categoryId: String,
    @kotlinx.serialization.SerialName("category_name") val categoryName: String,
    @kotlinx.serialization.SerialName("season_id") val seasonId: String,
    @kotlinx.serialization.SerialName("season_name") val seasonName: String,
    @kotlinx.serialization.SerialName("category_direct_to_final") val categoryDirectToFinal: Int?,
    @kotlinx.serialization.SerialName("category_in_semifinals") val categoryInSemifinals: Int?,
    @kotlinx.serialization.SerialName("season_direct_to_final") val seasonDirectToFinal: Int?,
    @kotlinx.serialization.SerialName("season_in_semifinals") val seasonInSemifinals: Int?,
    @kotlinx.serialization.SerialName("effective_direct_to_final") val effectiveDirectToFinal: Int,
    @kotlinx.serialization.SerialName("effective_in_semifinals") val effectiveInSemifinals: Int,
    @kotlinx.serialization.SerialName("config_source") val configSource: String
)

// Request to clear playoff config (set to null)
@Serializable
private data class ClearPlayoffConfigRequest(
    @kotlinx.serialization.SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @kotlinx.serialization.SerialName("players_in_semifinals") val playersInSemifinals: Int? = null
)
