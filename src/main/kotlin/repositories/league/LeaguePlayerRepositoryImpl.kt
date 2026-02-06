package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import models.league.CanDeletePlayerResponse
import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.LinkPlayerResponse
import models.league.MyLeagueRegistrationResponse
import models.league.PendingPlayerLinkResponse
import models.league.ReplacePlayerRequest
import models.league.ReplacePlayerResponse
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

    // Select query with user join - fetches CourtBit user profile when user_uid is set
    // Includes country_code and phone_number for WhatsApp integration
    private val selectWithUser = "*, user:user_uid(uid, first_name, last_name, photo_url, country_code, phone_number)"

    override suspend fun getAll(): List<LeaguePlayerResponse> {
        // First try with user join
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithUser)
            parameter("order", "name.asc")
        }

        if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            return json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        }

        // Fallback: try without user join (in case foreign key relation fails)
        val fallbackResponse = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("order", "name.asc")
        }

        return if (fallbackResponse.status.isSuccess()) {
            val bodyText = fallbackResponse.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getByCategoryId(categoryId: String): List<LeaguePlayerResponse> {
        // First try with user join
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithUser)
            parameter("category_id", "eq.$categoryId")
            parameter("order", "name.asc")
        }

        if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            return json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        }

        // Fallback: try without user join (in case foreign key relation fails)
        val fallbackResponse = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "name.asc")
        }

        return if (fallbackResponse.status.isSuccess()) {
            val bodyText = fallbackResponse.bodyAsText()
            json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeaguePlayerResponse? {
        // First try with user join
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithUser)
            parameter("id", "eq.$id")
        }

        if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            return list.firstOrNull()
        }

        // Fallback: try without user join
        val fallbackResponse = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("id", "eq.$id")
        }

        return if (fallbackResponse.status.isSuccess()) {
            val bodyText = fallbackResponse.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            list.firstOrNull()
        } else {
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
            false
        }
    }

    override suspend fun getByUserUidAndCategoryId(userUid: String, categoryId: String): LeaguePlayerResponse? {
        // First try with user join
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithUser)
            parameter("user_uid", "eq.$userUid")
            parameter("category_id", "eq.$categoryId")
        }

        if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            return list.firstOrNull()
        }

        // Fallback: try without user join
        val fallbackResponse = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("user_uid", "eq.$userUid")
            parameter("category_id", "eq.$categoryId")
        }

        return if (fallbackResponse.status.isSuccess()) {
            val bodyText = fallbackResponse.bodyAsText()
            val list = json.decodeFromString<List<LeaguePlayerResponse>>(bodyText)
            list.firstOrNull()
        } else {
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
                return@mapNotNull null
            }

            val season = seasonRepository?.getById(category.seasonId)
            if (season == null) {
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
                    colorHex = category.colorHex,
                    globalCategoryName = category.globalCategoryName
                )
            )
        }
    }

    // MARK: - Can Delete Player

    override suspend fun canDelete(playerId: String): CanDeletePlayerResponse {
        // Count matches where player appears in any of the 4 player fields
        val matchCountResponse = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("or", "(team1_player1_id.eq.$playerId,team1_player2_id.eq.$playerId,team2_player1_id.eq.$playerId,team2_player2_id.eq.$playerId)")
        }

        val matchCount = if (matchCountResponse.status.isSuccess()) {
            val bodyText = matchCountResponse.bodyAsText()
            try {
                json.decodeFromString<List<MatchIdOnly>>(bodyText).size
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }

        return CanDeletePlayerResponse(
            canDelete = matchCount == 0,
            hasMatches = matchCount > 0,
            matchCount = matchCount
        )
    }

    // MARK: - Replace Player

    override suspend fun replacePlayer(oldPlayerId: String, request: ReplacePlayerRequest): Result<ReplacePlayerResponse> {
        val payload = buildJsonObject {
            put("p_old_player_id", JsonPrimitive(oldPlayerId))
            put("p_name", JsonPrimitive(request.name))
            request.userUid?.let { put("p_user_uid", JsonPrimitive(it)) }
            request.email?.let { put("p_email", JsonPrimitive(it)) }
            request.phoneNumber?.let { put("p_phone_number", JsonPrimitive(it)) }
            request.shirtSize?.let { put("p_shirt_size", JsonPrimitive(it)) }
            request.shirtName?.let { put("p_shirt_name", JsonPrimitive(it)) }
        }

        val response = client.post("$apiUrl/rpc/replace_league_player") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            return Result.failure(IllegalStateException("Failed to replace player: $errorBody"))
        }

        val rpcResult = json.decodeFromString<RpcReplaceResult>(response.bodyAsText())
        val newPlayer = getById(rpcResult.newPlayerId)
            ?: return Result.failure(IllegalStateException("New player created but not found"))

        return Result.success(ReplacePlayerResponse(
            newPlayer = newPlayer,
            affectedDayGroups = rpcResult.affectedDayGroups,
            affectedMatches = rpcResult.affectedMatches
        ))
    }

    // MARK: - Player Linking

    override suspend fun findPendingLinks(email: String, phone: String?): List<PendingPlayerLinkResponse> {
        // Build OR clause for email or phone matching
        val orConditions = mutableListOf("email.ilike.$email")
        if (!phone.isNullOrBlank()) {
            // Normalize phone: remove all non-digits
            val digitsOnly = phone.replace(Regex("[^0-9]"), "")
            if (digitsOnly.isNotEmpty()) {
                // Strategy: Use the last 10 digits for matching (handles country code variations)
                // This covers cases like:
                // - User: +528112345678 → search for 8112345678
                // - Player stored as: 8112345678 ✓ matches
                // - Player stored as: +528112345678 → last 10 digits also match
                val phoneForSearch = if (digitsOnly.length > 10) {
                    digitsOnly.takeLast(10)
                } else {
                    digitsOnly
                }
                orConditions.add("phone_number.ilike.%$phoneForSearch%")
            }
        }

        // Query league_players with joins to get category and season info
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id,name,email,phone_number,category_id,league_categories!inner(id,name,color_hex,season_id,seasons!inner(id,name,is_active))")
            parameter("user_uid", "is.null")
            parameter("or", "(${orConditions.joinToString(",")})")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val bodyText = response.bodyAsText()

        // Parse the nested response
        val rawPlayers = try {
            json.decodeFromString<List<PendingPlayerLinkRaw>>(bodyText)
        } catch (e: Exception) {
            return emptyList()
        }

        // Filter for active seasons and map to response
        return rawPlayers
            .filter { it.category?.season?.isActive == true }
            .mapNotNull { raw ->
                val category = raw.category ?: return@mapNotNull null
                val season = category.season ?: return@mapNotNull null

                PendingPlayerLinkResponse(
                    id = raw.id,
                    name = raw.name,
                    email = raw.email,
                    phoneNumber = raw.phoneNumber,
                    categoryId = category.id,
                    categoryName = category.name,
                    categoryColor = category.colorHex,
                    seasonId = season.id,
                    seasonName = season.name
                )
            }
    }

    override suspend fun linkPlayerToUser(playerId: String, userUid: String): Result<LinkPlayerResponse> {
        // 1. Get the player to verify it exists and has no user_uid
        val player = getById(playerId)
            ?: return Result.failure(IllegalArgumentException("Player not found"))

        if (player.userUid != null) {
            return Result.failure(IllegalStateException("Player is already linked to an account"))
        }

        // 2. Update the player's user_uid
        val updatePayload = buildJsonObject {
            put("user_uid", JsonPrimitive(userUid))
        }

        val updateResponse = client.patch("$apiUrl/league_players?id=eq.$playerId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(updatePayload.toString())
        }

        if (!updateResponse.status.isSuccess()) {
            return Result.failure(IllegalStateException("Failed to link player: ${updateResponse.status}"))
        }

        // 3. Get the updated player with user info
        val updatedPlayer = getById(playerId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated player"))

        return Result.success(LinkPlayerResponse(
            linkedPlayer = updatedPlayer,
            message = "Player successfully linked to your account"
        ))
    }
}

// Helper data classes for parsing
@Serializable
private data class MatchIdOnly(val id: String)

@Serializable
private data class RpcReplaceResult(
    @SerialName("new_player_id") val newPlayerId: String,
    @SerialName("affected_day_groups") val affectedDayGroups: Int,
    @SerialName("affected_matches") val affectedMatches: Int
)

// Helper data classes for pending player links parsing
@Serializable
private data class PendingPlayerLinkRaw(
    val id: String,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("category_id") val categoryId: String,
    @SerialName("league_categories") val category: CategoryWithSeason?
)

@Serializable
private data class CategoryWithSeason(
    val id: String,
    val name: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("seasons") val season: SeasonBasic?
)

@Serializable
private data class SeasonBasic(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean
)
