package repositories.teams

import com.incodap.models.users.UserDto
import com.incodap.repositories.teams.TeamRepository
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import com.incodap.config.SupabaseConfig
import com.incodap.models.teams.RpcRegistrationRowDto
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.incodap.models.teams.SetTeamResultRequest
import com.incodap.models.teams.TeamResultStatusDto
import models.payments.RpcPayAndMarkManualDto
import models.ranking.TeamWithResultStatusDto
import models.teams.PendingTournamentPlayerLinkResponse
import models.teams.Team
import models.teams.TeamIdOnly
import models.teams.TeamRequest

class TeamRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : TeamRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun findTeam(tournamentId: String, playerA: String, playerB: String): Team? {
        val orFilter =
            "(and(player_a_uid.eq.$playerA,player_b_uid.eq.$playerB),and(player_a_uid.eq.$playerB,player_b_uid.eq.$playerA))"

        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("or", orFilter)
            parameter("select", "*,categories(*)")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(Team.serializer()), response.bodyAsText()).firstOrNull()
        } else null
    }

    override suspend fun createTeam(request: TeamRequest): Boolean {
        val response = client.post("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(listOf(request))
        }

        val success = response.status.isSuccess()
        if (!success) {
            val responseBody = runCatching { response.bodyAsText() }.getOrDefault("(No body)")
            // Cambia por tu logger preferido si usas uno
        } else {
        }
        return success
    }


    override suspend fun updateTeamPaidStatus(teamId: String, paidField: String, value: Boolean): Boolean {
        val patchBody = mapOf(paidField to value)

        val response = client.patch("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation") // para ver si afectó filas
            contentType(ContentType.Application.Json)
            setBody(patchBody)
        }

        val status = response.status.value
        val text = response.bodyAsText()

        // Supabase suele devolver 200/204 en éxito; 204 también es isSuccess()
        return response.status.isSuccess()
    }

    override suspend fun findTeamsByTournament(tournamentId: String): List<Team> {
        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "*,categories(*)")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(Team.serializer()), response.bodyAsText())
        } else emptyList()
    }

    override suspend fun findUsersByIds(userIds: List<String>): List<UserDto> {
        if (userIds.isEmpty()) return emptyList()
        val inClause = userIds.joinToString(",") { "\"$it\"" }

        val response = client.get("$apiUrl/users") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("uid", "in.($inClause)")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(UserDto.serializer()), response.bodyAsText())
        } else emptyList()
    }

    override suspend fun deleteTeam(teamId: String): Boolean {
        val response = client.delete("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
        }

        return response.status.isSuccess()
    }

    override suspend fun markPlayerAsPaid(tournamentId: String, playerUid: String, partnerUid: String): Boolean {
        val team = findTeam(tournamentId, playerUid, partnerUid) ?: return false
        val field = if (team.playerAUid == playerUid) "player_a_paid" else "player_b_paid"
        return updateTeamPaidStatus(team.id, field, true)
    }

    override suspend fun markBothPlayersAsPaid(tournamentId: String, playerUid: String, partnerUid: String): Boolean {
        val team = findTeam(tournamentId, playerUid, partnerUid) ?: return false
        val aSuccess = updateTeamPaidStatus(team.id, "player_a_paid", true)
        val bSuccess = updateTeamPaidStatus(team.id, "player_b_paid", true)
        return aSuccess && bSuccess
    }

    override suspend fun findByPlayerAndCategory(playerUid: String, tournamentId: String, categoryId: Int): Team? {
        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            parameter("or", "(player_a_uid.eq.$playerUid,player_b_uid.eq.$playerUid)")
            parameter("limit", "1")
            parameter("select", "*,categories(*)")
        }

        return runCatching {
            json.decodeFromString(ListSerializer(Team.serializer()), response.bodyAsText()).firstOrNull()
        }.getOrNull()
    }

    override suspend fun hasTeamsForTournament(tournamentId: String): Boolean {
        val response = client.get("${config.apiUrl}/teams") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "id")
            parameter("limit", 1)
        }
        return response.status.isSuccess() && response.bodyAsText().contains("id")
    }

    override suspend fun hasTeamsForTournamentAndCategoryName(tournamentId: String, categoryName: String): Boolean {
        val response = client.get("${config.apiUrl}/teams") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category", "eq.$categoryName")
            parameter("select", "id")
            parameter("limit", 1)
        }

        if (!response.status.isSuccess()) return false

        val body = response.bodyAsText()
        val teams = Json.decodeFromString<List<TeamIdOnly>>(body)
        return teams.isNotEmpty()
    }

    override suspend fun findTeamById(teamId: String): Team? {
        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$teamId")
            parameter("select", "*,categories(*)")
            parameter("limit", 1)
        }
        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(Team.serializer()), response.bodyAsText()).firstOrNull()
        } else null
    }

    override suspend fun updateTeamCategory(teamId: String, newCategoryId: Int): Boolean {
        val patchBody = mapOf("category_id" to newCategoryId)
        val response = client.patch("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(patchBody)
        }
        return response.status.isSuccess()
    }

    override suspend fun updateTeamRestriction(teamId: String, restriction: String): Boolean {
        val patchBody = mapOf("restriction" to restriction)
        val response = client.patch("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(patchBody)
        }
        return response.status.isSuccess()
    }

    override suspend fun setTeamResult(teamId: String, req: SetTeamResultRequest): String {
        val payload = buildJsonObject {
            put("p_team_id", teamId)
            put("p_position", req.position)
            put("p_points_awarded", req.pointsAwarded)
            put("p_season", req.season)
            req.adminUid?.let { put("p_admin_uid", it) }
        }

        // ⬇️ Cambiamos al RPC "apply" (NO delta/replace)
        val response = client.post("$apiUrl/rpc/set_team_result_and_apply_points") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        val body = response.bodyAsText()
        if (response.status == HttpStatusCode.Conflict) {
            throw IllegalStateException("409: Result already exists. $body")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("RPC set_team_result_and_apply_points failed: ${response.status} $body")
        }

        // Supabase devuelve un string con el uuid
        return body.trim().trim('"')
    }

    override suspend fun unsetTeamResult(teamId: String) {
        val payload = buildJsonObject { put("p_team_id", teamId) }

        val response = client.post("$apiUrl/rpc/unset_team_result_and_revert_points") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("RPC unset_team_result_and_revert_points failed: ${response.status} ${response.bodyAsText()}")
        }
    }

    override suspend fun getTeamsWithResultStatus(tournamentId: String): List<TeamWithResultStatusDto> {
        val response = client.get("$apiUrl/teams_with_result_status") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            url {
                parameters.append("tournament_id", "eq.$tournamentId")
                parameters.append("order", "category_id.asc")
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("GET teams_with_result_status failed: ${response.status} ${response.bodyAsText()}")
        }
        return json.decodeFromString(ListSerializer(TeamWithResultStatusDto.serializer()), response.bodyAsText())
    }

    override suspend fun getTeamResultStatus(teamId: String): TeamResultStatusDto? {
        val response = client.get("$apiUrl/team_results") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            url {
                parameters.append("team_id", "eq.$teamId")
                // Solo traemos lo necesario
                parameters.append("select", "id,team_id,position,points_awarded,season,updated_at")
                parameters.append("limit", "1")
            }
        }

        if (!response.status.isSuccess()) {
            return null
        }

        val body = response.bodyAsText()
        val list = json.decodeFromString(
            ListSerializer(TeamResultStatusDto.serializer()),
            body
        )
        return list.firstOrNull()
    }

    override suspend fun payAndMarkManual(
        teamId: String,
        tournamentId: String,
        paidBy: String,
        method: String,
        adminUid: String,
        playerUid: String?
    ): Boolean {
        val dto = RpcPayAndMarkManualDto(
            p_team_id = teamId,
            p_tournament_id = tournamentId,
            p_paid_by = paidBy,
            p_method = method,
            p_admin_uid = adminUid,
            p_player_uid = playerUid
        )

        val response = client.post("$apiUrl/rpc/pay_and_mark_manual") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }

        val body = response.bodyAsText()

        return response.status.isSuccess()
    }

    override suspend fun getUserRegistrationsInTournament(
        userUid: String,
        tournamentId: String
    ): List<RpcRegistrationRowDto> {
        val payload = buildJsonObject {
            put("p_user_uid", userUid)
            put("p_tournament_id", tournamentId)
        }

        val response = client.post("$apiUrl/rpc/get_user_registrations_in_tournament") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return emptyList()
        }

        return json.decodeFromString(
            ListSerializer(RpcRegistrationRowDto.serializer()),
            body
        )
    }

    override suspend fun getUserRegistrations(
        userUid: String,
        status: String?,
        limit: Int,
        offset: Int
    ): List<RpcRegistrationRowDto> {
        val payload = buildJsonObject {
            put("p_user_uid", userUid)
            status?.let { put("p_status", it.lowercase()) } // active|upcoming|past
            put("p_limit", limit)
            put("p_offset", offset)
        }

        val response = client.post("$apiUrl/rpc/get_user_registrations") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return emptyList()
        }

        return json.decodeFromString(
            ListSerializer(RpcRegistrationRowDto.serializer()),
            body
        )
    }

    override suspend fun findPendingTournamentLinks(
        email: String,
        phone: String?
    ): List<PendingTournamentPlayerLinkResponse> {
        // Build filter for teams with manual players matching email or phone
        val emailLower = email.lowercase()
        val phoneLast10 = phone?.takeLast(10)

        // Query for player A matches
        val orConditions = mutableListOf<String>()
        orConditions.add("player_a_email.ilike.$emailLower")
        if (phoneLast10 != null) {
            orConditions.add("player_a_phone.ilike.%$phoneLast10")
        }
        orConditions.add("player_b_email.ilike.$emailLower")
        if (phoneLast10 != null) {
            orConditions.add("player_b_phone.ilike.%$phoneLast10")
        }

        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("or", "(${orConditions.joinToString(",")})")
            parameter("select", "*,categories(*),tournaments(id,name)")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val body = response.bodyAsText()
        // Parse and transform results
        return try {
            val teams = json.decodeFromString(ListSerializer(TeamWithTournamentDto.serializer()), body)
            teams.flatMap { team ->
                val results = mutableListOf<PendingTournamentPlayerLinkResponse>()

                // Check player A
                if (team.playerAUid == null && team.playerAName != null) {
                    val matchesEmail = team.playerAEmail?.lowercase() == emailLower
                    val matchesPhone = phoneLast10 != null && team.playerAPhone?.takeLast(10) == phoneLast10
                    if (matchesEmail || matchesPhone) {
                        results.add(
                            PendingTournamentPlayerLinkResponse(
                                teamId = team.id,
                                playerPosition = "a",
                                name = team.playerAName,
                                email = team.playerAEmail,
                                phone = team.playerAPhone,
                                tournamentId = team.tournament?.id ?: team.tournamentId,
                                tournamentName = team.tournament?.name ?: "",
                                categoryName = team.category.name
                            )
                        )
                    }
                }

                // Check player B
                if (team.playerBUid == null && team.playerBName != null) {
                    val matchesEmail = team.playerBEmail?.lowercase() == emailLower
                    val matchesPhone = phoneLast10 != null && team.playerBPhone?.takeLast(10) == phoneLast10
                    if (matchesEmail || matchesPhone) {
                        results.add(
                            PendingTournamentPlayerLinkResponse(
                                teamId = team.id,
                                playerPosition = "b",
                                name = team.playerBName,
                                email = team.playerBEmail,
                                phone = team.playerBPhone,
                                tournamentId = team.tournament?.id ?: team.tournamentId,
                                tournamentName = team.tournament?.name ?: "",
                                categoryName = team.category.name
                            )
                        )
                    }
                }

                results
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun linkTournamentPlayerToUser(
        teamId: String,
        playerPosition: String,
        userUid: String
    ): Boolean {
        val uidField = if (playerPosition == "a") "player_a_uid" else "player_b_uid"
        val nameField = if (playerPosition == "a") "player_a_name" else "player_b_name"
        val emailField = if (playerPosition == "a") "player_a_email" else "player_b_email"
        val phoneField = if (playerPosition == "a") "player_a_phone" else "player_b_phone"

        // Update: set UID and clear manual fields
        val patchBody = mapOf(
            uidField to userUid,
            nameField to null,
            emailField to null,
            phoneField to null
        )

        val response = client.patch("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(patchBody)
        }

        val success = response.status.isSuccess()
        if (success) {
        } else {
        }
        return success
    }

    override suspend fun replacePlayer(
        teamId: String,
        playerPosition: String,
        newPlayerUid: String?,
        newPlayerName: String?,
        newPlayerEmail: String?,
        newPlayerPhone: String?
    ): Boolean {
        val uidField = if (playerPosition == "a") "player_a_uid" else "player_b_uid"
        val nameField = if (playerPosition == "a") "player_a_name" else "player_b_name"
        val emailField = if (playerPosition == "a") "player_a_email" else "player_b_email"
        val phoneField = if (playerPosition == "a") "player_a_phone" else "player_b_phone"
        val paidField = if (playerPosition == "a") "player_a_paid" else "player_b_paid"

        // Build patch body using JsonObject for proper null handling
        val patchBody = buildJsonObject {
            if (newPlayerUid != null) {
                // Registered CourtBit user - set UID, clear manual fields, reset payment
                put(uidField, newPlayerUid)
                put(nameField, kotlinx.serialization.json.JsonNull)
                put(emailField, kotlinx.serialization.json.JsonNull)
                put(phoneField, kotlinx.serialization.json.JsonNull)
            } else {
                // Manual player - set manual fields, clear UID, reset payment
                put(uidField, kotlinx.serialization.json.JsonNull)
                put(nameField, newPlayerName)
                put(emailField, newPlayerEmail)
                put(phoneField, newPlayerPhone)
            }
            put(paidField, false)
        }

        val response = client.patch("$apiUrl/teams?id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(patchBody.toString())
        }

        return response.status.isSuccess()
    }
}

/**
 * DTO for teams with tournament info for pending links query
 */
@kotlinx.serialization.Serializable
private data class TeamWithTournamentDto(
    val id: String,
    @kotlinx.serialization.SerialName("tournament_id") val tournamentId: String,
    @kotlinx.serialization.SerialName("player_a_uid") val playerAUid: String? = null,
    @kotlinx.serialization.SerialName("player_b_uid") val playerBUid: String? = null,
    @kotlinx.serialization.SerialName("player_a_name") val playerAName: String? = null,
    @kotlinx.serialization.SerialName("player_a_email") val playerAEmail: String? = null,
    @kotlinx.serialization.SerialName("player_a_phone") val playerAPhone: String? = null,
    @kotlinx.serialization.SerialName("player_b_name") val playerBName: String? = null,
    @kotlinx.serialization.SerialName("player_b_email") val playerBEmail: String? = null,
    @kotlinx.serialization.SerialName("player_b_phone") val playerBPhone: String? = null,
    @kotlinx.serialization.SerialName("categories") val category: models.category.CategoryResponseDto,
    @kotlinx.serialization.SerialName("tournaments") val tournament: TournamentMinimalDto? = null
)

@kotlinx.serialization.Serializable
private data class TournamentMinimalDto(
    val id: String,
    val name: String
)
