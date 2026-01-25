package repositories.bracket

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import models.bracket.*

class BracketRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : BracketRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

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
            val brackets = json.decodeFromString<List<BracketResponse>>(bodyText)
            brackets.firstOrNull()
        } else {
            println("BracketRepository.getBracket failed: ${response.status}")
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

        val matches = if (matchesResponse.status.isSuccess()) {
            val bodyText = matchesResponse.bodyAsText()
            json.decodeFromString<List<MatchResponse>>(bodyText)
        } else {
            println("BracketRepository.getBracketWithMatches matches failed: ${matchesResponse.status}")
            emptyList()
        }

        return BracketWithMatchesResponse(bracket = bracket, matches = matches)
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
                isBye = match.isBye
                // next_match_id will be set in a second pass after we have UUIDs
            )
        }

        val response = client.post("$apiUrl/tournament_matches") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(insertRequests)
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
        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "next_match_id" to nextMatchId,
                "next_match_position" to position
            ))
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

        val response = client.patch("$apiUrl/tournament_matches?id=eq.$matchId") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "score_team1" to scoreTeam1,
                "score_team2" to scoreTeam2,
                "set_scores" to setScoresJson,
                "winner_team" to winnerTeam,
                "status" to "completed"
            ))
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(no body)" }
        println("BracketRepository.updateMatchScore -> ${status.value}\nBody: $bodyText")

        return if (status.isSuccess()) {
            val matches = json.decodeFromString<List<MatchResponse>>(bodyText)
            matches.firstOrNull()?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Match not found after update"))
        } else {
            Result.failure(IllegalStateException("Failed to update score: ${status.value}"))
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
}
