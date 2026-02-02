package repositories.bracket

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    @SerialName("court_number") val courtNumber: Int,
    @SerialName("scheduled_time") val scheduledTime: String,
    val status: String = "scheduled"
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
        println("🔍 [getBracket] Looking for bracket with tournamentId=$tournamentId, categoryId=$categoryId")
        val response = client.get("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val brackets = json.decodeFromString<List<BracketResponse>>(bodyText)
            val bracket = brackets.firstOrNull()
            println("✅ [getBracket] Found bracket: id=${bracket?.id}, format=${bracket?.format}, tournamentId=${bracket?.tournamentId}")
            bracket
        } else {
            println("❌ [getBracket] Failed: ${response.status}")
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
            val brackets = json.decodeFromString<List<BracketResponse>>(bodyText)
            brackets.firstOrNull()
        } else {
            println("BracketRepository.getBracketById failed: ${response.status}")
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
            val matchList = json.decodeFromString<List<MatchResponse>>(bodyText)
            matchList
        } else {
            println("❌ [getBracketWithMatches] Matches query failed: ${matchesResponse.status}")
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
            // Warn if a team exists but has no players at all (neither linked nor manual)
            if (match.groupNumber == null) {
                if (match.team1Id != null && team1 != null &&
                    team1.playerAUid == null && team1.playerBUid == null &&
                    team1.playerAName.isNullOrBlank() && team1.playerBName.isNullOrBlank()) {
                    println("⚠️ [getBracketWithMatches] Match ${match.matchNumber}: team1 (${match.team1Id}) has NO players (linked or manual)!")
                }
                if (match.team2Id != null && team2 != null &&
                    team2.playerAUid == null && team2.playerBUid == null &&
                    team2.playerAName.isNullOrBlank() && team2.playerBName.isNullOrBlank()) {
                    println("⚠️ [getBracketWithMatches] Match ${match.matchNumber}: team2 (${match.team2Id}) has NO players (linked or manual)!")
                }
            }
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
            val teams = json.decodeFromString<List<TeamDto>>(teamsBody)
            teams.associateBy { it.id }
        } else {
            println("BracketRepository.getTeamsMap failed: ${teamsResponse.status}")
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
            println("BracketRepository.getPlayersForBracket teams failed: ${teamsResponse.status}")
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
            } else {
                println("BracketRepository.getPlayersForBracket profiles failed: ${playersResponse.status}")
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

    override suspend fun getBracketsByTournament(tournamentId: String): List<BracketResponse> {
        val response = client.get("$apiUrl/tournament_brackets") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "*")
            parameter("order", "category_id.asc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<BracketResponse>>(bodyText)
        } else {
            println("BracketRepository.getBracketsByTournament failed: ${response.status}")
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

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.createBracket -> ${status.value} ${status.description}\nBody: $bodyText")

        if (!status.isSuccess()) return null

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
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.createMatches -> ${status.value}\nBody: $bodyText")

        if (!status.isSuccess()) return emptyList()

        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<MatchResponse>>(it)
        } ?: emptyList()
    }

    override suspend fun updateMatchNextMatchId(matchId: String, nextMatchId: String, position: Int): Boolean {
        val jsonBody = """{"next_match_id":"$nextMatchId","next_match_position":$position}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
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

        val responseStatus = response.status
        println("BracketRepository.updateBracketStatus -> ${responseStatus.value}")
        return responseStatus.isSuccess()
    }

    override suspend fun deleteBracket(bracketId: String): Boolean {
        // Delete matches first (foreign key constraint)
        val matchesResponse = client.delete("$apiUrl/tournament_matches?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        if (!matchesResponse.status.isSuccess()) {
            println("BracketRepository.deleteBracket matches failed: ${matchesResponse.status}")
            // Continue anyway - matches might not exist
        }

        // Delete bracket
        val bracketResponse = client.delete("$apiUrl/tournament_brackets?id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        val status = bracketResponse.status
        println("BracketRepository.deleteBracket -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun deleteMatchesByBracketId(bracketId: String): Boolean {
        val response = client.delete("$apiUrl/tournament_matches?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }

        val status = response.status
        println("BracketRepository.deleteMatchesByBracketId -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun deleteMatchesByIds(matchIds: List<String>): Int {
        if (matchIds.isEmpty()) return 0

        // Delete matches using IN query
        val response = client.delete("$apiUrl/tournament_matches?id=in.(${matchIds.joinToString(",")})") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
        }

        val status = response.status
        println("BracketRepository.deleteMatchesByIds -> ${status.value}, ${matchIds.size} matches")

        // Return count of deleted matches
        return if (status.isSuccess()) matchIds.size else 0
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
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<MatchResponse>>(bodyText).firstOrNull()
        } else {
            println("BracketRepository.getMatch failed: ${response.status}")
            null
        }
    }

    override suspend fun updateMatchScore(
        matchId: String,
        scoreTeam1: Int,
        scoreTeam2: Int,
        setScores: List<SetScore>,
        winnerTeam: Int
    ): Result<MatchResponse> {
        val setScoresJson = json.encodeToString(setScores)

        val jsonBody = """{"score_team1":$scoreTeam1,"score_team2":$scoreTeam2,"set_scores":$setScoresJson,"winner_team":$winnerTeam,"status":"completed"}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.updateMatchScore(matchId=$matchId) -> ${status.value}\nBody: $bodyText")

        return if (status.isSuccess()) {
            val matches = runCatching {
                json.decodeFromString<List<MatchResponse>>(bodyText)
            }.getOrElse { e ->
                println("Failed to parse response: ${e.message}")
                return Result.failure(IllegalStateException("Failed to parse Supabase response: ${e.message}"))
            }

            if (matches.isEmpty()) {
                // Supabase returns 200 with empty array when no rows match
                return Result.failure(IllegalArgumentException("Match not found with ID: $matchId"))
            }

            Result.success(matches.first())
        } else {
            Result.failure(IllegalStateException("Failed to update score: ${status.value} - $bodyText"))
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

        val status = response.status
        println("BracketRepository.advanceWinner -> ${status.value} (field: $fieldToUpdate)")

        return if (status.isSuccess()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Failed to advance winner to next match: ${status.value}"))
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
            println("BracketRepository.getStandings failed: ${response.status}")
            emptyList()
        }
    }

    override suspend fun upsertStandings(standings: List<StandingInput>): Boolean {
        if (standings.isEmpty()) return true

        // Delete existing standings for this bracket first (simple approach for upsert)
        val bracketId = standings.first().bracketId
        deleteStandings(bracketId)

        // Convert to serializable insert requests
        val insertRequests = standings.map { s ->
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
                groupNumber = s.groupNumber
            )
        }

        // Use jsonForBulkInsert to ensure all objects have the same keys
        val jsonBody = jsonForBulkInsert.encodeToString(insertRequests)

        // Debug: print the JSON being sent
        println("📤 [upsertStandings] Sending ${standings.size} standings, JSON sample: ${jsonBody.take(500)}...")

        val response = client.post("$apiUrl/tournament_standings") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }

        val status = response.status
        val responseBody = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.upsertStandings -> ${status.value}")
        if (!status.isSuccess()) {
            println("❌ [upsertStandings] Error response: $responseBody")
        }
        return status.isSuccess()
    }

    override suspend fun deleteStandings(bracketId: String): Boolean {
        val response = client.delete("$apiUrl/tournament_standings?bracket_id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        val status = response.status
        println("BracketRepository.deleteStandings -> ${status.value}")
        return status.isSuccess()
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

        val responseStatus = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.updateMatchStatus -> ${responseStatus.value}\nBody: $bodyText")

        if (!responseStatus.isSuccess()) {
            throw IllegalStateException("Failed to update match status: ${responseStatus.value}")
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
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<MatchResponse>>(bodyText)
        } else {
            println("BracketRepository.getMatchesForTeam failed: ${response.status}")
            emptyList()
        }
    }

    override suspend fun updateMatchForfeit(matchId: String, winnerTeam: Int): Boolean {
        val jsonBody = """{"status":"forfeit","winner_team":$winnerTeam}"""

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }

        val status = response.status
        println("BracketRepository.updateMatchForfeit -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun advanceToNextMatch(matchId: String, winnerId: String, nextMatchId: String, position: Int): Boolean {
        val fieldToUpdate = if (position == 1) "team1_id" else "team2_id"

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$nextMatchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf(fieldToUpdate to winnerId))
        }

        val status = response.status
        println("BracketRepository.advanceToNextMatch -> ${status.value} (field: $fieldToUpdate, next: $nextMatchId)")
        return status.isSuccess()
    }

    // ============ Groups + Knockout ============

    override suspend fun updateBracketConfig(bracketId: String, configJson: String): Boolean {
        val response = client.patch("$apiUrl/tournament_brackets?id=eq.$bracketId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody("""{"config":$configJson}""")
        }

        val status = response.status
        println("BracketRepository.updateBracketConfig -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun updateMatchTeams(matchId: String, team1Id: String?, team2Id: String?, groupNumber: Int?): Boolean {
        val updateMap = mutableMapOf<String, Any?>()
        updateMap["team1_id"] = team1Id
        updateMap["team2_id"] = team2Id
        if (groupNumber != null) {
            updateMap["group_number"] = groupNumber
        }

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(updateMap)
        }

        val status = response.status
        println("BracketRepository.updateMatchTeams -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun updateStandingGroupNumber(bracketId: String, teamId: String, groupNumber: Int): Boolean {
        val response = client.patch("$apiUrl/tournament_standings?bracket_id=eq.$bracketId&team_id=eq.$teamId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf("group_number" to groupNumber))
        }

        val status = response.status
        println("BracketRepository.updateStandingGroupNumber -> ${status.value}")
        return status.isSuccess()
    }

    override suspend fun updateMatchSchedule(matchId: String, courtNumber: Int, scheduledTime: String): MatchResponse {
        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(MatchScheduleUpdateDto(courtNumber, scheduledTime))
        }

        val responseStatus = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.updateMatchSchedule -> ${responseStatus.value}\nBody: $bodyText")

        if (!responseStatus.isSuccess()) {
            throw IllegalStateException("Failed to update match schedule: ${responseStatus.value}")
        }

        val matches = json.decodeFromString<List<MatchResponse>>(bodyText)
        return matches.firstOrNull()
            ?: throw IllegalStateException("Match not found after update")
    }
}
