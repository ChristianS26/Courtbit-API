package repositories.ranking

import com.incodap.config.SupabaseConfig
import com.incodap.repositories.ranking.RankingRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.NotFoundException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.ranking.AddRankingEventRequest
import models.ranking.BatchRankingRpcResponse
import models.ranking.PlayerProfileResponse
import models.ranking.PlayerTournamentHistoryItem
import models.ranking.Ranking
import models.ranking.RankingEventWithTournament
import models.ranking.RankingItemResponse

class RankingRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : RankingRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun addRankingEvent(request: AddRankingEventRequest): String {
        val response = client.post("$apiUrl/rpc/add_ranking_event_and_update") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Error calling add_ranking_event_and_update: ${response.status} ${response.bodyAsText()}")
        }
        return response.bodyAsText().replace("\"", "")
    }

    override suspend fun getTournamentName(tournamentId: String): String? {
        return try {
            val response = client.get("$apiUrl/tournaments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "name")
                parameter("id", "eq.$tournamentId")
                parameter("limit", "1")
            }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsText()
            // Response is like [{"name":"Tournament Name"}]
            val match = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)
            match?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun batchAddRankingEvents(
        tournamentId: String,
        categoryId: Int,
        season: String,
        entries: List<Map<String, Any?>>,
        tournamentName: String?
    ): BatchRankingRpcResponse {
        val entriesJson = JsonArray(entries.map { entry ->
            buildJsonObject {
                entry["user_id"]?.let { put("user_id", it as String) }
                entry["team_member_id"]?.let { put("team_member_id", it as String) }
                put("points", entry["points"] as Int)
                put("position", entry["position"] as String)
                entry["team_result_id"]?.let { put("team_result_id", it as String) }
            }
        })

        val body = buildJsonObject {
            put("p_tournament_id", tournamentId)
            put("p_category_id", categoryId)
            put("p_season", season)
            put("p_entries", entriesJson)
            tournamentName?.let { put("p_tournament_name", it) }
        }

        val response = client.post("$apiUrl/rpc/batch_add_ranking_events") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Error calling batch_add_ranking_events: ${response.status} ${response.bodyAsText()}")
        }
        return json.decodeFromString(BatchRankingRpcResponse.serializer(), response.bodyAsText())
    }

    override suspend fun checkExistingEvents(tournamentId: String, categoryId: Int): Boolean {
        val response = client.get("$apiUrl/ranking_events") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return false
        val body = response.bodyAsText()
        return body != "[]" && body.isNotBlank()
    }

    override suspend fun getRanking(season: String?, categoryId: Int?, organizerId: String?): List<RankingItemResponse> {
        return try {
            val response = client.get("$apiUrl/ranking") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter(
                    "select",
                    "category:categories(*),total_points,user:users!inner(uid,first_name,last_name,photo_url,phone)"
                )
                if (season != null) parameter("season", "eq.$season")
                if (categoryId != null) parameter("category_id", "eq.$categoryId")
                if (organizerId != null) parameter("organizer_id", "eq.$organizerId")
            }
            json.decodeFromString(ListSerializer(RankingItemResponse.serializer()), response.bodyAsText())
        } catch (e: Exception) {
            emptyList()
        }
    }


    override suspend fun getRankingByUser(userId: String, season: String?): List<Ranking> {
        val response = client.get("$apiUrl/ranking_entries") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("user_id", "eq.$userId")
            if (season != null) {
                parameter("season", "eq.$season")
            }
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(Ranking.serializer()), response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun getPlayerProfile(userId: String, categoryId: Int, season: String?): PlayerProfileResponse {
        val effectiveSeason = season ?: java.time.Year.now().toString()

        val rankingResponse = client.get("$apiUrl/ranking") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter(
                "select",
                "user:users!inner(uid,first_name,last_name,photo_url,phone),total_points,category:categories(*)"
            )
            parameter("season", "eq.$effectiveSeason")
            parameter("category_id", "eq.$categoryId")
            parameter("order", "total_points.desc,user_id.asc")
        }

        val rankingList = json.decodeFromString(
            ListSerializer(RankingItemResponse.serializer()),
            rankingResponse.bodyAsText()
        )

        val indexed = rankingList.withIndex()
        val (position, item) = indexed.firstOrNull { it.value.user.uid == userId }
            ?.let { it.index + 1 to it.value }
            ?: throw NotFoundException("Jugador no encontrado")

        // 2. Obtener ranking_events con join a tournaments
        val eventsResponse = client.get("$apiUrl/ranking_events") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter(
                "select",
                "tournament:tournaments(id,name,start_date),tournament_id,tournament_name,position,created_at,points_earned"
            )
            parameter("user_id", "eq.$userId")
            parameter("category_id", "eq.$categoryId")
        }

        val events = json.decodeFromString(
            ListSerializer(RankingEventWithTournament.serializer()),
            eventsResponse.bodyAsText()
        )

        // 3. Calcular torneos ganados y finales
        val tournamentsWon = events.count { it.position == "1" }
        val finalsReached = events.count { it.position == "2" }

        // 4. Historial por torneo
        val history = events.map {
            PlayerTournamentHistoryItem(
                tournamentId = it.tournamentId.orEmpty(),
                tournamentName = it.tournament?.name ?: it.tournamentName ?: "Torneo eliminado",
                date = it.tournament?.startDate ?: it.createdAt,
                result = when (it.position) {
                    "1" -> "Campeón"
                    "2" -> "Finalista"
                    "3" -> "Semifinalista"
                    "4" -> "Cuartos de final"
                    "5" -> "Octavos de final"
                    else -> "Participante"
                }
            )
        }

        return PlayerProfileResponse(
            user = item.user,
            position = position,
            points = item.totalPoints,
            tournamentsWon = tournamentsWon,
            finalsReached = finalsReached,
            history = history,
            category = item.category
        )
    }

    override suspend fun getRankingForMultipleUsersAndCategories(
        userIds: List<String>,
        categoryIds: List<Int>,
        season: String?
    ): List<Ranking> {
        if (userIds.isEmpty() || categoryIds.isEmpty()) return emptyList()

        val userClause = userIds.joinToString(",") { "\"$it\"" }
        val categoryClause = categoryIds.joinToString(",") { "\"$it\"" }

        val response = client.get("$apiUrl/ranking") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("user_id", "in.($userClause)")
            parameter("category_id", "in.($categoryClause)")
            if (season != null) {
                parameter("season", "eq.$season")
            }
            parameter("select", "*,category:category_id(*)") // ¡ESTE ES EL JOIN!
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(Ranking.serializer()), response.bodyAsText())
        } else {
            emptyList()
        }
    }
}
