package repositories.bracket

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.bracket.*

@Serializable
private data class TeamDto(
    val id: String,
    @SerialName("player_a_uid") val playerAUid: String?,
    @SerialName("player_b_uid") val playerBUid: String?,
    // Manual player fields (for players not linked to user accounts)
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null
)

@Serializable
private data class MatchScheduleUpdateDto(
    @SerialName("court_number") val courtNumber: Int?,
    @SerialName("scheduled_time") val scheduledTime: String?
)

class BracketRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : BracketRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    // Json with explicitNulls=true for Supabase bulk inserts (all objects must have same keys)
    private val jsonForBulkInsert = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true  // Include nulls so all objects have the same keys
    }

    override suspend fun getBracket(tournamentId: String, categoryId: Int): BracketResponse? {
        val response = client.get("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<BracketResponse>>(bodyText).firstOrNull()
        } else {
            null
        }
    }

    override suspend fun getBracketById(bracketId: String): BracketResponse? {
        val response = client.get("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$bracketId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<BracketResponse>>(bodyText).firstOrNull()
        } else {
            null
        }
    }

    override suspend fun getBracketWithMatches(tournamentId: String, categoryId: Int): BracketWithMatchesResponse? {
        // First get the bracket
        val bracket = getBracket(tournamentId, categoryId) ?: return null

        // Then get all matches for this bracket
        val matchesResponse = client.get("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "eq.${bracket.id}")
            parameter("select", "*")
            parameter("order", "round_number.asc,match_number.asc")
        }

        var matches = if (matchesResponse.status.isSuccess()) {
            val bodyText = matchesResponse.bodyAsText()
            json.decodeFromString<List<MatchResponse>>(bodyText)
        } else {
            emptyList()
        }

        // Get teams to populate player IDs in matches
        val teamsMap = getTeamsMap(matches)

        // Helper to get player ID (UID for linked players, or generated ID for manual players)
        fun getPlayerId(team: TeamDto?, playerType: Char): String? {
            if (team == null) return null
            return when (playerType) {
                'a' -> team.playerAUid ?: if (!team.playerAName.isNullOrBlank()) "manual-${team.id}-a" else null
                'b' -> team.playerBUid ?: if (!team.playerBName.isNullOrBlank()) "manual-${team.id}-b" else null
                else -> null
            }
        }

        // Enrich matches with player IDs from teams (supports both linked and manual players)
        matches = matches.map { match ->
            val team1 = match.team1Id?.let { teamsMap[it] }
            val team2 = match.team2Id?.let { teamsMap[it] }
            match.copy(
                team1Player1Id = getPlayerId(team1, 'a'),
                team1Player2Id = getPlayerId(team1, 'b'),
                team2Player1Id = getPlayerId(team2, 'a'),
                team2Player2Id = getPlayerId(team2, 'b')
            )
        }

        // Get standings for this bracket
        val standings = getStandings(bracket.id)

        // Get players involved in the matches
        val players = getPlayersForBracket(bracket.id, matches)

        return BracketWithMatchesResponse(
            bracket = bracket,
            matches = matches,
            standings = standings,
            players = players
        )
    }

    /**
     * Get teams as a map for quick lookup
     */
    private suspend fun getTeamsMap(matches: List<MatchResponse>): Map<String, TeamDto> {
        val teamIds = matches.flatMap { match ->
            listOfNotNull(match.team1Id, match.team2Id)
        }.distinct()

        if (teamIds.isEmpty()) return emptyMap()

        val teamIdsFilter = teamIds.joinToString(",") { "\"$it\"" }
        val teamsResponse = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "in.($teamIdsFilter)")
            parameter("select", "id,player_a_uid,player_b_uid,player_a_name,player_b_name")
        }

        return if (teamsResponse.status.isSuccess()) {
            val teamsBody = teamsResponse.bodyAsText()
            json.decodeFromString<List<TeamDto>>(teamsBody).associateBy { it.id }
        } else {
            emptyMap()
        }
    }

    /**
     * Get players involved in the bracket matches by looking up teams.
     * Supports both linked players (with UIDs) and manual players (with names only).
     */
    private suspend fun getPlayersForBracket(bracketId: String, matches: List<MatchResponse>): List<BracketPlayerInfo> {
        // Collect all team IDs from matches
        val teamIds = matches.flatMap { match ->
            listOfNotNull(match.team1Id, match.team2Id)
        }.distinct()

        if (teamIds.isEmpty()) return emptyList()

        // Query teams table to get player UIDs and manual player names
        val teamIdsFilter = teamIds.joinToString(",") { "\"$it\"" }
        val teamsResponse = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "in.($teamIdsFilter)")
            parameter("select", "id,player_a_uid,player_b_uid,player_a_name,player_b_name")
        }

        if (!teamsResponse.status.isSuccess()) {
            return emptyList()
        }

        val teamsBody = teamsResponse.bodyAsText()
        val teams = json.decodeFromString<List<TeamDto>>(teamsBody)

        val allPlayers = mutableListOf<BracketPlayerInfo>()

        // Collect all player UIDs for linked players
        val playerUids = teams.flatMap { team ->
            listOfNotNull(team.playerAUid, team.playerBUid)
        }.distinct()

        // Get linked player profiles from users table
        if (playerUids.isNotEmpty()) {
            val playerUidsFilter = playerUids.joinToString(",") { "\"$it\"" }
            val playersResponse = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("uid", "in.($playerUidsFilter)")
                parameter("select", "uid,first_name,last_name,email,photo_url")
            }

            if (playersResponse.status.isSuccess()) {
                val playersBody = playersResponse.bodyAsText()
                val linkedPlayers = json.decodeFromString<List<BracketPlayerInfo>>(playersBody)
                allPlayers.addAll(linkedPlayers)
            }
        }

        // Create player info for manual players (players without UIDs but with names)
        for (team in teams) {
            // Player A - if no UID but has name, create manual player entry
            if (team.playerAUid == null && !team.playerAName.isNullOrBlank()) {
                val manualId = "manual-${team.id}-a"
                val playerName = team.playerAName!! // Safe - already checked not null/blank
                val nameParts = playerName.split(" ", limit = 2)
                allPlayers.add(BracketPlayerInfo(
                    uid = manualId,
                    firstName = nameParts.first(),
                    lastName = nameParts.getOrNull(1) ?: "",
                    email = null,
                    photoUrl = null
                ))
            }
            // Player B - if no UID but has name, create manual player entry
            if (team.playerBUid == null && !team.playerBName.isNullOrBlank()) {
                val manualId = "manual-${team.id}-b"
                val playerName = team.playerBName!! // Safe - already checked not null/blank
                val nameParts = playerName.split(" ", limit = 2)
                allPlayers.add(BracketPlayerInfo(
                    uid = manualId,
                    firstName = nameParts.first(),
                    lastName = nameParts.getOrNull(1) ?: "",
                    email = null,
                    photoUrl = null
                ))
            }
        }

        return allPlayers
    }

    override suspend fun getAllBracketsWithMatches(tournamentId: String): List<BracketWithMatchesResponse> {
        val brackets = getBracketsByTournament(tournamentId)
        if (brackets.isEmpty()) return emptyList()

        // Fetch all matches for all brackets in one query
        val bracketIds = brackets.map { it.id }
        val bracketIdsFilter = bracketIds.joinToString(",") { "\"$it\"" }

        val matchesResponse = client.get("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "in.($bracketIdsFilter)")
            parameter("select", "*")
            parameter("order", "round_number.asc,match_number.asc")
        }

        val allMatches = if (matchesResponse.status.isSuccess()) {
            json.decodeFromString<List<MatchResponse>>(matchesResponse.bodyAsText())
        } else {
            emptyList()
        }

        // Fetch all teams referenced in matches (one query)
        val teamsMap = getTeamsMap(allMatches)

        // Enrich matches with player IDs
        fun getPlayerId(team: TeamDto?, playerType: Char): String? {
            if (team == null) return null
            return when (playerType) {
                'a' -> team.playerAUid ?: if (!team.playerAName.isNullOrBlank()) "manual-${team.id}-a" else null
                'b' -> team.playerBUid ?: if (!team.playerBName.isNullOrBlank()) "manual-${team.id}-b" else null
                else -> null
            }
        }

        val enrichedMatches = allMatches.map { match ->
            val team1 = match.team1Id?.let { teamsMap[it] }
            val team2 = match.team2Id?.let { teamsMap[it] }
            match.copy(
                team1Player1Id = getPlayerId(team1, 'a'),
                team1Player2Id = getPlayerId(team1, 'b'),
                team2Player1Id = getPlayerId(team2, 'a'),
                team2Player2Id = getPlayerId(team2, 'b')
            )
        }

        // Group matches by bracket ID
        val matchesByBracket = enrichedMatches.groupBy { it.bracketId }

        // Fetch all standings in one query
        val standingsResponse = client.get("$apiUrl/tournament_standings") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "in.($bracketIdsFilter)")
            parameter("select", "*")
            parameter("order", "group_number.asc.nullsfirst,position.asc")
        }

        val allStandings = if (standingsResponse.status.isSuccess()) {
            json.decodeFromString<List<StandingEntry>>(standingsResponse.bodyAsText())
        } else {
            emptyList()
        }
        val standingsByBracket = allStandings.groupBy { it.bracketId }

        // Fetch all players in one pass
        val allPlayers = getPlayersForBracket("bulk", enrichedMatches)

        // Assemble per-bracket responses
        return brackets.map { bracket ->
            val bracketMatches = matchesByBracket[bracket.id] ?: emptyList()
            val bracketStandings = standingsByBracket[bracket.id] ?: emptyList()
            BracketWithMatchesResponse(
                bracket = bracket,
                matches = bracketMatches,
                standings = bracketStandings,
                players = allPlayers
            )
        }
    }

    override suspend fun getBracketsByTournament(tournamentId: String): List<BracketResponse> {
        val response = client.get("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "*")
            parameter("order", "category_id.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<BracketResponse>>(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun createBracket(
        tournamentId: String,
        categoryId: Int,
        format: String,
        seedingMethod: String
    ): BracketResponse? {
        val insertRequest = BracketInsertRequest(
            tournamentId = tournamentId,
            categoryId = categoryId,
            format = format,
            status = "draft",
            seedingMethod = seedingMethod
        )

        val response = client.post("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(insertRequest)) // Supabase expects array
        }

        if (!response.status.isSuccess()) return null

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { return null }
        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<BracketResponse>>(it).firstOrNull()
        }
    }

    override suspend fun createMatches(bracketId: String, matches: List<GeneratedMatch>): List<MatchResponse> {
        if (matches.isEmpty()) return emptyList()

        val insertRequests = matches.map { match ->
            MatchInsertRequest(
                bracketId = bracketId,
                roundNumber = match.roundNumber,
                matchNumber = match.matchNumber,
                roundName = match.roundName,
                team1Id = match.team1Id,
                team2Id = match.team2Id,
                status = match.status,
                isBye = match.isBye,
                groupNumber = match.groupNumber
                // next_match_id will be set in a second pass after we have UUIDs
            )
        }

        // Use jsonForBulkInsert to ensure all objects have the same keys (including nulls)
        val jsonBody = jsonForBulkInsert.encodeToString(insertRequests)

        val response = client.post("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }

        if (!response.status.isSuccess()) return emptyList()

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { return emptyList() }
        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<MatchResponse>>(it)
        } ?: emptyList()
    }

    override suspend fun updateMatchNextMatchId(matchId: String, nextMatchId: String, position: Int): Boolean {
        val jsonBody = """{"next_match_id":"$nextMatchId","next_match_position":$position}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }

        return response.status.isSuccess()
    }

    override suspend fun updateBracketStatus(bracketId: String, status: String): Boolean {
        val response = client.patch("$apiUrl/tournament_brackets?id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to status))
        }

        return response.status.isSuccess()
    }

    override suspend fun getMatchesByBracketId(bracketId: String): List<MatchResponse> {
        val response = client.get("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "eq.$bracketId")
            parameter("select", "id,status")
            parameter("order", "match_number.asc")
        }
        return if (response.status.isSuccess()) {
            json.decodeFromString<List<MatchResponse>>(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun deleteBracket(bracketId: String): Boolean {
        // Delete standings first (foreign key constraint)
        client.delete("$apiUrl/tournament_standings?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        // Delete matches (foreign key constraint)
        client.delete("$apiUrl/tournament_matches?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        // Delete bracket
        val bracketResponse = client.delete("$apiUrl/tournament_brackets?id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        return bracketResponse.status.isSuccess()
    }

    override suspend fun deleteMatchesByBracketId(bracketId: String): Boolean {
        val response = client.delete("$apiUrl/tournament_matches?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        return response.status.isSuccess()
    }

    override suspend fun deleteMatchesByIds(matchIds: List<String>): Int {
        if (matchIds.isEmpty()) return 0

        // Delete matches using IN query
        val response = client.delete("$apiUrl/tournament_matches?id=in.(${matchIds.joinToString(",")})") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
        }

        return if (response.status.isSuccess()) matchIds.size else 0
    }

    // ============ Match Scoring ============

    override suspend fun getMatch(matchId: String): MatchResponse? {
        val response = client.get("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$matchId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<MatchResponse>>(response.bodyAsText()).firstOrNull()
        } else {
            null
        }
    }

    override suspend fun updateMatchScore(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        expectedVersion: Int?
    ): Result<MatchResponse> {
        val setScoresJson = json.encodeToString(setScores)
        val newVersion = (expectedVersion ?: 1) + 1

        val jsonBody = """{"score_team1":$scoreTeam1,"score_team2":$scoreTeam2,"set_scores":$setScoresJson,"winner_team":$winnerTeam,"status":"completed","version":$newVersion}"""

        // If expectedVersion is provided, add version filter for optimistic locking
        val queryFilter = if (expectedVersion != null) {
            "$apiUrl/tournament_matches?id=eq.$matchId&version=eq.$expectedVersion"
        } else {
            "$apiUrl/tournament_matches?id=eq.$matchId"
        }

        val response = client.patch(queryFilter) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        return if (response.status.isSuccess()) {
            val matches = runCatching {
                json.decodeFromString<List<MatchResponse>>(bodyText)
            }.getOrElse { e ->
                return Result.failure(IllegalStateException("Failed to parse Supabase response: ${e.message}"))
            }

            if (matches.isEmpty()) {
                // If expectedVersion was provided and no rows matched, it's a version conflict
                if (expectedVersion != null) {
                    return Result.failure(ConcurrentModificationException("Match was updated by another user. Please refresh and try again."))
                }
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }

            Result.success(matches.first())
        } else {
            Result.failure(IllegalStateException("Failed to update score: ${response.status.value} - $bodyText"))
        }
    }

    override suspend fun updateMatchScoreAndAdvance(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        expectedVersion: Int?,
        submittedByUserId: String?
    ): Result<Pair<MatchResponse, Boolean>> {
        val setScoresJson = json.encodeToString(setScores)

        val body = buildJsonObject {
            put("p_match_id", matchId)
            put("p_score_team1", scoreTeam1)
            put("p_score_team2", scoreTeam2)
            put("p_set_scores", Json.parseToJsonElement(setScoresJson))
            put("p_winner_team", winnerTeam)
            if (expectedVersion != null) put("p_expected_version", expectedVersion)
            if (submittedByUserId != null) put("p_submitted_by_user_id", submittedByUserId)
        }

        val response = client.post("$apiUrl/rpc/update_match_score_and_advance") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        if (!response.status.isSuccess()) {
            // Parse Supabase error to distinguish concurrent modification from other errors
            if (bodyText.contains("CONCURRENT_MODIFICATION")) {
                return Result.failure(ConcurrentModificationException("Match was updated by another user. Please refresh and try again."))
            }
            if (bodyText.contains("MATCH_NOT_FOUND")) {
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }
            return Result.failure(IllegalStateException("Failed to update score atomically: ${response.status.value} - $bodyText"))
        }

        return try {
            val resultJson = Json.parseToJsonElement(bodyText).jsonObject
            val advanced = resultJson["advanced"]?.let {
                it is JsonPrimitive && it.boolean
            } ?: false

            // Parse match from the returned JSON (exclude the 'advanced' field)
            val matchJson = buildJsonObject {
                resultJson.forEach { (key, value) ->
                    if (key != "advanced") put(key, value)
                }
            }
            val match = json.decodeFromString<MatchResponse>(matchJson.toString())
            Result.success(Pair(match, advanced))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to parse atomic update response: ${e.message}"))
        }
    }

    override suspend fun resetMatchScore(matchId: String): Result<MatchResponse> {
        val jsonBody = """{"score_team1":null,"score_team2":null,"set_scores":null,"winner_team":null,"status":"pending"}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        return if (response.status.isSuccess()) {
            val matches = runCatching {
                json.decodeFromString<List<MatchResponse>>(bodyText)
            }.getOrElse { e ->
                return Result.failure(IllegalStateException("Failed to parse response: ${e.message}"))
            }
            if (matches.isEmpty()) {
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }
            Result.success(matches.first())
        } else {
            Result.failure(IllegalStateException("Failed to reset score: ${response.status.value} - $bodyText"))
        }
    }

    override suspend fun resetMatchScoreAtomic(matchId: String): Result<MatchResponse> {
        val body = buildJsonObject {
            put("p_match_id", matchId)
        }

        val response = client.post("$apiUrl/rpc/reset_match_score_atomic") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        if (!response.status.isSuccess()) {
            if (bodyText.contains("MATCH_NOT_FOUND")) {
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }
            if (bodyText.contains("MATCH_NOT_COMPLETED")) {
                return Result.failure(IllegalStateException("El partido no tiene resultado para borrar"))
            }
            if (bodyText.contains("NEXT_MATCH_STARTED")) {
                return Result.failure(IllegalStateException("No se puede borrar: el siguiente partido ya comenzó"))
            }
            return Result.failure(IllegalStateException("Failed to reset score: ${response.status.value} - $bodyText"))
        }

        return try {
            val match = json.decodeFromString<MatchResponse>(bodyText)
            Result.success(match)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to parse reset response: ${e.message}"))
        }
    }

    override suspend fun advanceWinner(matchId: String, winnerTeamId: String): Result<Unit> {
        // Get current match to find next_match_id and next_match_position
        val currentMatch = getMatch(matchId)
            ?: return Result.failure(IllegalStateException("Match not found"))

        val nextMatchId = currentMatch.nextMatchId
            ?: return Result.success(Unit)  // Finals have no next match

        val position = currentMatch.nextMatchPosition
            ?: return Result.failure(IllegalStateException("Missing next_match_position"))

        // Update next match with advancing team
        val fieldToUpdate = if (position == 1) "team1_id" else "team2_id"

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$nextMatchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf(fieldToUpdate to winnerTeamId))
        }

        return if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to advance winner to next match: ${response.status.value}"))
        }
    }

    // ============ Standings ============

    override suspend fun getStandings(bracketId: String): List<StandingEntry> {
        val response = client.get("$apiUrl/tournament_standings") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "eq.$bracketId")
            parameter("order", "position.asc")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<StandingEntry>>(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun upsertStandings(standings: List<StandingInput>): Boolean {
        if (standings.isEmpty()) return true

        val bracketId = standings.first().bracketId

        // Step 1: Delete existing standings for this bracket
        val deleteResponse = client.delete("$apiUrl/tournament_standings?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        if (!deleteResponse.status.isSuccess()) {
            println("[upsertStandings] Failed to delete standings for bracket $bracketId: ${deleteResponse.status}")
            return false
        }

        // Step 2: Insert new standings
        val insertPayload = standings.map { s ->
            StandingInsertRequest(
                bracketId = s.bracketId,
                teamId = s.teamId,
                playerId = s.playerId,
                position = s.position,
                totalPoints = s.totalPoints,
                matchesPlayed = s.matchesPlayed,
                matchesWon = s.matchesWon,
                matchesLost = s.matchesLost,
                gamesWon = s.gamesWon,
                gamesLost = s.gamesLost,
                pointDifference = s.pointDifference,
                roundReached = s.roundReached,
                groupNumber = s.groupNumber
            )
        }

        val insertBody = json.encodeToString(insertPayload)
        val insertResponse = client.post("$apiUrl/tournament_standings") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            setBody(TextContent(insertBody, ContentType.Application.Json))
        }

        if (!insertResponse.status.isSuccess()) {
            val errorBody = runCatching { insertResponse.bodyAsText() }.getOrElse { "(no body)" }
            println("[upsertStandings] Failed to insert standings for bracket $bracketId: ${insertResponse.status} - $errorBody")
            return false
        }

        return true
    }

    override suspend fun deleteStandings(bracketId: String): Boolean {
        val response = client.delete("$apiUrl/tournament_standings?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        return response.status.isSuccess()
    }

    // ============ Status and Withdrawal ============

    override suspend fun updateMatchStatus(matchId: String, status: String): MatchResponse {
        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to status))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to update match status: ${response.status.value}")
        }

        val matches = json.decodeFromString<List<MatchResponse>>(bodyText)
        return matches.firstOrNull()
            ?: throw IllegalStateException("Match not found after update")
    }

    override suspend fun getMatchesForTeam(bracketId: String, teamId: String): List<MatchResponse> {
        // Query matches where team1_id or team2_id equals teamId
        val response = client.get("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("bracket_id", "eq.$bracketId")
            parameter("or", "(team1_id.eq.$teamId,team2_id.eq.$teamId)")
            parameter("select", "*")
            parameter("order", "round_number.asc,match_number.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<MatchResponse>>(response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun updateMatchForfeit(matchId: String, winnerTeam: Int): Boolean {
        val jsonBody = """{"status":"forfeit","winner_team":$winnerTeam}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }

        return response.status.isSuccess()
    }

    override suspend fun advanceToNextMatch(matchId: String, winnerId: String, nextMatchId: String, position: Int): Boolean {
        val fieldToUpdate = if (position == 1) "team1_id" else "team2_id"

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$nextMatchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf(fieldToUpdate to winnerId))
        }

        return response.status.isSuccess()
    }

    // ============ Groups + Knockout ============

    override suspend fun updateBracketConfig(bracketId: String, configJson: String): Boolean {
        val response = client.patch("$apiUrl/tournament_brackets?id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            setBody(TextContent("""{"config":$configJson}""", ContentType.Application.Json))
        }

        return response.status.isSuccess()
    }

    override suspend fun updateMatchTeams(matchId: String, team1Id: String?, team2Id: String?, groupNumber: Int?): Boolean {
        val body = buildJsonObject {
            put("team1_id", team1Id)
            put("team2_id", team2Id)
            if (groupNumber != null) {
                put("group_number", groupNumber)
            }
        }

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

        return response.status.isSuccess()
    }

    override suspend fun updateStandingGroupNumber(bracketId: String, teamId: String, groupNumber: Int): Boolean {
        val response = client.patch("$apiUrl/tournament_standings?bracket_id=eq.$bracketId&team_id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf("group_number" to groupNumber))
        }

        return response.status.isSuccess()
    }

    override suspend fun swapTeamsInGroupsAtomic(bracketId: String, team1Id: String, team2Id: String): Result<Unit> {
        val body = buildJsonObject {
            put("p_bracket_id", bracketId)
            put("p_team1_id", team1Id)
            put("p_team2_id", team2Id)
        }

        val response = client.post("$apiUrl/rpc/swap_teams_in_groups") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }

        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
            val errorMessage = when {
                bodyText.contains("TEAM1_HAS_PLAYED") -> "El primer equipo ya tiene partidos jugados y no puede ser movido"
                bodyText.contains("TEAM2_HAS_PLAYED") -> "El segundo equipo ya tiene partidos jugados y no puede ser movido"
                bodyText.contains("TEAM1_NOT_FOUND") -> "Team 1 not found in any group"
                bodyText.contains("TEAM2_NOT_FOUND") -> "Team 2 not found in any group"
                bodyText.contains("SAME_GROUP") -> "Teams are already in the same group"
                else -> "Failed to swap teams: ${response.status.value} - $bodyText"
            }
            return Result.failure(IllegalStateException(errorMessage))
        }

        return Result.success(Unit)
    }

    override suspend fun updateMatchSchedule(matchId: String, courtNumber: Int?, scheduledTime: String?): MatchResponse {
        // Use jsonForBulkInsert which has explicitNulls=true to send null values to Supabase
        val bodyJson = jsonForBulkInsert.encodeToString(MatchScheduleUpdateDto.serializer(), MatchScheduleUpdateDto(courtNumber, scheduledTime))

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            setBody(TextContent(bodyJson, ContentType.Application.Json))
        }

        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to update match schedule: ${response.status.value}")
        }

        val matches = json.decodeFromString<List<MatchResponse>>(bodyText)
        return matches.firstOrNull()
            ?: throw IllegalStateException("Match not found after update")
    }

    // ============ Player Score Submission ============

    override suspend fun getTeamPlayerUids(teamId: String): List<String> {
        val response = client.get("$apiUrl/teams") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$teamId")
            parameter("select", "player_a_uid,player_b_uid")
        }

        if (!response.status.isSuccess()) return emptyList()

        val teams = json.decodeFromString<List<TeamDto>>(response.bodyAsText())
        val team = teams.firstOrNull() ?: return emptyList()
        return listOfNotNull(team.playerAUid, team.playerBUid)
    }

    override suspend fun getTournamentAllowPlayerScores(tournamentId: String): Boolean {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$tournamentId")
            parameter("select", "allow_player_scores")
        }

        if (!response.status.isSuccess()) return false

        @Serializable
        data class AllowPlayerScoresRow(
            @SerialName("allow_player_scores") val allowPlayerScores: Boolean = false
        )

        val rows = json.decodeFromString<List<AllowPlayerScoresRow>>(response.bodyAsText())
        return rows.firstOrNull()?.allowPlayerScores ?: false
    }

    override suspend fun updateMatchScoreWithAudit(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int,
        submittedByUserId: String,
        expectedVersion: Int?
    ): Result<MatchResponse> {
        val setScoresJson = json.encodeToString(setScores)
        val now = java.time.Instant.now().toString()
        val newVersion = (expectedVersion ?: 1) + 1
        val bodyJson = """
            {
                "score_team1": $scoreTeam1,
                "score_team2": $scoreTeam2,
                "set_scores": $setScoresJson,
                "winner_team": $winnerTeam,
                "status": "completed",
                "submitted_by_user_id": "$submittedByUserId",
                "submitted_at": "$now",
                "version": $newVersion
            }
        """.trimIndent()

        val queryFilter = if (expectedVersion != null) {
            "$apiUrl/tournament_matches?id=eq.$matchId&version=eq.$expectedVersion"
        } else {
            "$apiUrl/tournament_matches?id=eq.$matchId"
        }

        val response = client.patch(queryFilter) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            setBody(TextContent(bodyJson, ContentType.Application.Json))
        }

        return if (response.status.isSuccess()) {
            val matches = json.decodeFromString<List<MatchResponse>>(response.bodyAsText())
            if (matches.isEmpty()) {
                if (expectedVersion != null) {
                    return Result.failure(ConcurrentModificationException("Match was updated by another user. Please refresh and try again."))
                }
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }
            Result.success(matches.first())
        } else {
            Result.failure(IllegalStateException("Failed to update match score: ${response.status.value}"))
        }
    }
}
