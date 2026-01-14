package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.MyLeagueRegistrationResponse
import models.league.SelfRegisterRequest
import models.league.UpdateLeaguePlayerRequest

class LeaguePlayerRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val categoryRepository: LeagueCategoryRepository? = null,
    private val seasonRepository: SeasonRepository? = null
) : LeaguePlayerRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<LeaguePlayerResponse> {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            println("❌ Error getAll league players: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getByCategoryId(categoryId: String): List<LeaguePlayerResponse> {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            println("❌ Error getByCategoryId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeaguePlayerResponse? {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            list.firstOrNull()
        } else {
            println("❌ Error getById: ${response.status}")
            null
        }
    }

    override suspend fun create(request: CreateLeaguePlayerRequest): LeaguePlayerResponse? {
        // Get category's max_players (default to 16 if not available)
        val maxPlayers = categoryRepository?.getById(request.categoryId)?.maxPlayers ?: 16

        // Check how many active players (not on waiting list) already exist
        val existingPlayers = getByCategoryId(request.categoryId)
        val activePlayers = existingPlayers.filter { !it.isWaitingList }
        val isWaitingList = activePlayers.size >= maxPlayers

        // Create a modified request with is_waiting_list set
        val requestWithWaitingList = if (isWaitingList) {
            kotlinx.serialization.json.buildJsonObject {
                put("category_id", kotlinx.serialization.json.JsonPrimitive(request.categoryId))
                put("name", kotlinx.serialization.json.JsonPrimitive(request.name))
                request.userUid?.let { put("user_uid", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.email?.let { put("email", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.phoneNumber?.let { put("phone_number", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.shirtSize?.let { put("shirt_size", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.shirtName?.let { put("shirt_name", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("is_waiting_list", kotlinx.serialization.json.JsonPrimitive(true))
            }
        } else {
            kotlinx.serialization.json.buildJsonObject {
                put("category_id", kotlinx.serialization.json.JsonPrimitive(request.categoryId))
                put("name", kotlinx.serialization.json.JsonPrimitive(request.name))
                request.userUid?.let { put("user_uid", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.email?.let { put("email", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.phoneNumber?.let { put("phone_number", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.shirtSize?.let { put("shirt_size", kotlinx.serialization.json.JsonPrimitive(it)) }
                request.shirtName?.let { put("shirt_name", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("is_waiting_list", kotlinx.serialization.json.JsonPrimitive(false))
            }
        }

        val url = "$apiUrl/league_players"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(requestWithWaitingList))
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }

        if (!status.isSuccess()) return null

        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<LeaguePlayerResponse>>(it).firstOrNull()
        }
    }

    override suspend fun update(id: String, request: UpdateLeaguePlayerRequest): Boolean {
        val response = client.patch("$apiUrl/league_players?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/league_players?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            println("🧨 Supabase DELETE exception for league player $id: ${e.stackTraceToString()}")
            false
        }
    }

    override suspend fun getByUserUidAndCategoryId(userUid: String, categoryId: String): LeaguePlayerResponse? {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("user_uid", "eq.$userUid")
            parameter("category_id", "eq.$categoryId")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            list.firstOrNull()
        } else {
            println("❌ Error getByUserUidAndCategoryId: ${response.status}")
            null
        }
    }

    override suspend fun selfRegister(userUid: String, request: SelfRegisterRequest): Result<LeaguePlayerResponse> {
        // 1. Get the category to validate it exists
        val category = categoryRepository?.getById(request.categoryId)
            ?: return Result.failure(IllegalArgumentException("Category not found"))

        // 2. Get the season to check if registrations are open
        val season = seasonRepository?.getById(category.seasonId)
            ?: return Result.failure(IllegalArgumentException("Season not found"))

        if (!season.registrationsOpen) {
            return Result.failure(IllegalStateException("Registrations are closed for this season"))
        }

        // 3. Check if user is already registered in this category
        val existingPlayer = getByUserUidAndCategoryId(userUid, request.categoryId)
        if (existingPlayer != null) {
            return Result.failure(IllegalStateException("You are already registered in this category"))
        }

        // 4. Create the player using the existing create method
        val createRequest = CreateLeaguePlayerRequest(
            categoryId = request.categoryId,
            userUid = userUid,
            name = request.name,
            email = request.email,
            phoneNumber = request.phoneNumber,
            discountAmount = 0,
            discountReason = null,
            shirtSize = request.shirtSize,
            shirtName = request.shirtName
        )

        val created = create(createRequest)
        return if (created != null) {
            Result.success(created)
        } else {
            Result.failure(IllegalStateException("Failed to create player registration"))
        }
    }

    override suspend fun getMyRegistrations(userUid: String): List<MyLeagueRegistrationResponse> {
        // Get all players for this user
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("user_uid", "eq.$userUid")
            parameter("order", "created_at.desc")
        }

        if (!response.status.isSuccess()) {
            println("❌ Error getMyRegistrations: ${response.status}")
            return emptyList()
        }

        val bodyText = response.bodyAsText()
        val players = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)

        if (players.isEmpty()) {
            return emptyList()
        }

        // For each player, get category and season info
        return players.mapNotNull { player ->
            val category = categoryRepository?.getById(player.categoryId)
            if (category == null) {
                println("⚠️ Category not found for player ${player.id}")
                return@mapNotNull null
            }

            val season = seasonRepository?.getById(category.seasonId)
            if (season == null) {
                println("⚠️ Season not found for category ${category.id}")
                return@mapNotNull null
            }

            MyLeagueRegistrationResponse(
                playerId = player.id,
                isWaitingList = player.isWaitingList,
                hasPaid = player.hasPaid,
                registeredAt = player.createdAt,
                season = MyLeagueRegistrationResponse.SeasonInfo(
                    id = season.id,
                    name = season.name,
                    startDate = season.startDate,
                    endDate = season.endDate,
                    isActive = season.isActive,
                    organizerName = season.organizerName
                ),
                category = MyLeagueRegistrationResponse.CategoryInfo(
                    id = category.id,
                    name = category.name,
                    level = category.level,
                    colorHex = category.colorHex
                )
            )
        }
    }
}
