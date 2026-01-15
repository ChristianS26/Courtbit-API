package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import models.league.CanDeletePlayerResponse
import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.MyLeagueRegistrationResponse
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
    private val selectWithUser = "*, user:user_uid(uid, first_name, last_name, photo_url)"

    override suspend fun getAll(): List<LeaguePlayerResponse> {
        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", selectWithUser)
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
            parameter("select", selectWithUser)
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
            parameter("select", selectWithUser)
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
            parameter("select", selectWithUser)
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
                println("⚠️ Error parsing match count: ${e.message}")
                0
            }
        } else {
            println("⚠️ Error checking matches for player $playerId: ${matchCountResponse.status}")
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
        // 1. Get the old player to verify it exists and get categoryId
        val oldPlayer = getById(oldPlayerId)
            ?: return Result.failure(IllegalArgumentException("Player not found"))

        // 2. Create the new player - force is_waiting_list = false since replacing an active player
        val newPlayerPayload = buildJsonObject {
            put("category_id", JsonPrimitive(oldPlayer.categoryId))
            put("name", JsonPrimitive(request.name))
            request.userUid?.let { put("user_uid", JsonPrimitive(it)) }
            request.email?.let { put("email", JsonPrimitive(it)) }
            request.phoneNumber?.let { put("phone_number", JsonPrimitive(it)) }
            request.shirtSize?.let { put("shirt_size", JsonPrimitive(it)) }
            request.shirtName?.let { put("shirt_name", JsonPrimitive(it)) }
            put("is_waiting_list", JsonPrimitive(false))
        }

        val createResponse = client.post("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(newPlayerPayload))
        }

        if (!createResponse.status.isSuccess()) {
            return Result.failure(IllegalStateException("Failed to create new player: ${createResponse.status}"))
        }

        val newPlayer = try {
            json.decodeFromString<List<LeaguePlayerResponse>>(createResponse.bodyAsText()).firstOrNull()
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Failed to parse new player response: ${e.message}"))
        } ?: return Result.failure(IllegalStateException("Empty response when creating new player"))

        // 3. Update all day_groups where old player exists in player_ids array
        val affectedDayGroups = updateDayGroupsPlayerIds(oldPlayerId, newPlayer.id)

        // 4. Update all doubles_matches where old player exists
        val affectedMatches = updateDoublesMatchesPlayerIds(oldPlayerId, newPlayer.id)

        // 5. Delete the old player
        val deleteSuccess = delete(oldPlayerId)
        if (!deleteSuccess) {
            println("⚠️ Warning: Failed to delete old player $oldPlayerId after replacement")
        }

        return Result.success(ReplacePlayerResponse(
            newPlayer = newPlayer,
            affectedDayGroups = affectedDayGroups,
            affectedMatches = affectedMatches
        ))
    }

    private suspend fun updateDayGroupsPlayerIds(oldPlayerId: String, newPlayerId: String): Int {
        // Get all day_groups containing the old player ID
        val dayGroupsResponse = client.get("$apiUrl/day_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id,player_ids")
            parameter("player_ids", "cs.{$oldPlayerId}") // contains
        }

        if (!dayGroupsResponse.status.isSuccess()) {
            println("⚠️ Error fetching day_groups for replacement: ${dayGroupsResponse.status}")
            return 0
        }

        val dayGroups = try {
            json.decodeFromString<List<DayGroupPlayerIds>>(dayGroupsResponse.bodyAsText())
        } catch (e: Exception) {
            println("⚠️ Error parsing day_groups: ${e.message}")
            return 0
        }

        var updatedCount = 0

        for (dayGroup in dayGroups) {
            // Replace old player ID with new player ID in the array
            val updatedPlayerIds = dayGroup.playerIds.map {
                if (it == oldPlayerId) newPlayerId else it
            }

            val updatePayload = buildJsonObject {
                put("player_ids", JsonArray(updatedPlayerIds.map { JsonPrimitive(it) }))
            }

            val updateResponse = client.patch("$apiUrl/day_groups?id=eq.${dayGroup.id}") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(updatePayload.toString())
            }

            if (updateResponse.status.isSuccess()) {
                updatedCount++
            } else {
                println("⚠️ Failed to update day_group ${dayGroup.id}: ${updateResponse.status}")
            }
        }

        return updatedCount
    }

    private suspend fun updateDoublesMatchesPlayerIds(oldPlayerId: String, newPlayerId: String): Int {
        // Find all matches with the old player in any position
        val matchesResponse = client.get("$apiUrl/doubles_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id,team1_player1_id,team1_player2_id,team2_player1_id,team2_player2_id")
            parameter("or", "(team1_player1_id.eq.$oldPlayerId,team1_player2_id.eq.$oldPlayerId,team2_player1_id.eq.$oldPlayerId,team2_player2_id.eq.$oldPlayerId)")
        }

        if (!matchesResponse.status.isSuccess()) {
            println("⚠️ Error fetching matches for replacement: ${matchesResponse.status}")
            return 0
        }

        val matches = try {
            json.decodeFromString<List<MatchPlayerIds>>(matchesResponse.bodyAsText())
        } catch (e: Exception) {
            println("⚠️ Error parsing matches: ${e.message}")
            return 0
        }

        var updatedCount = 0

        for (match in matches) {
            val updatePayload = buildJsonObject {
                if (match.team1Player1Id == oldPlayerId) put("team1_player1_id", JsonPrimitive(newPlayerId))
                if (match.team1Player2Id == oldPlayerId) put("team1_player2_id", JsonPrimitive(newPlayerId))
                if (match.team2Player1Id == oldPlayerId) put("team2_player1_id", JsonPrimitive(newPlayerId))
                if (match.team2Player2Id == oldPlayerId) put("team2_player2_id", JsonPrimitive(newPlayerId))
            }

            val updateResponse = client.patch("$apiUrl/doubles_matches?id=eq.${match.id}") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(updatePayload.toString())
            }

            if (updateResponse.status.isSuccess()) {
                updatedCount++
            } else {
                println("⚠️ Failed to update match ${match.id}: ${updateResponse.status}")
            }
        }

        return updatedCount
    }
}

// Helper data classes for parsing
@Serializable
private data class MatchIdOnly(val id: String)

@Serializable
private data class DayGroupPlayerIds(
    val id: String,
    @SerialName("player_ids") val playerIds: List<String>
)

@Serializable
private data class MatchPlayerIds(
    val id: String,
    @SerialName("team1_player1_id") val team1Player1Id: String?,
    @SerialName("team1_player2_id") val team1Player2Id: String?,
    @SerialName("team2_player1_id") val team2Player1Id: String?,
    @SerialName("team2_player2_id") val team2Player2Id: String?
)
