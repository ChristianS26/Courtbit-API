package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.league.*

class LeaguePaymentRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : LeaguePaymentRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getById(id: String): LeaguePaymentResponse? {
        val response = client.get("$apiUrl/league_payments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePaymentResponse>>(bodyText).firstOrNull()
        } else {
            println("Error getById league payment: ${response.status}")
            null
        }
    }

    override suspend fun getByPlayerId(playerId: String): List<LeaguePaymentResponse> {
        val response = client.get("$apiUrl/league_payments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("league_player_id", "eq.$playerId")
            parameter("order", "paid_at.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePaymentResponse>>(bodyText)
        } else {
            println("Error getByPlayerId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getBySeasonId(seasonId: String): List<LeaguePaymentResponse> {
        val response = client.get("$apiUrl/league_payments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("order", "paid_at.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePaymentResponse>>(bodyText)
        } else {
            println("Error getBySeasonId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun create(
        request: CreateLeaguePaymentRequest,
        registeredByUid: String
    ): LeaguePaymentResponse? {
        val insertDto = LeaguePaymentInsertDto(
            league_player_id = request.leaguePlayerId,
            season_id = request.seasonId,
            category_id = request.categoryId,
            amount = request.amount,
            currency = request.currency,
            method = request.method,
            status = "succeeded",
            notes = request.notes,
            registered_by_uid = registeredByUid,
            registered_by_email = request.registeredByEmail,
            paid_at = request.paidAt
        )

        val response = client.post("$apiUrl/league_payments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(insertDto))
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<LeaguePaymentResponse>>(bodyText).firstOrNull()
        } else {
            println("Error creating league payment: ${response.status} - ${response.bodyAsText()}")
            null
        }
    }

    override suspend fun update(id: String, request: UpdateLeaguePaymentRequest): Boolean {
        val updateBody = buildJsonObject {
            request.amount?.let { put("amount", it) }
            request.method?.let { put("method", it) }
            request.status?.let { put("status", it) }
            request.notes?.let { put("notes", it) }
            request.paidAt?.let { put("paid_at", it) }
        }

        val response = client.patch("$apiUrl/league_payments?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(updateBody)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/league_payments?id=eq.$id") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            println("Error deleting league payment $id: ${e.message}")
            false
        }
    }

    override suspend fun getPlayerPaymentSummary(playerId: String): PlayerPaymentSummary? {
        val rpcBody = buildJsonObject {
            put("p_league_player_id", playerId)
        }

        val response = client.post("$apiUrl/rpc/get_league_player_payment_summary") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(rpcBody)
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            try {
                // RPC returns an array, get first element
                json.decodeFromString<List<PlayerPaymentSummary>>(bodyText).firstOrNull()
            } catch (e: Exception) {
                println("Error parsing payment summary: ${e.message}")
                null
            }
        } else {
            println("Error getPlayerPaymentSummary: ${response.status} - ${response.bodyAsText()}")
            null
        }
    }

    override suspend fun getSeasonPaymentReport(seasonId: String): List<SeasonPaymentReportRow> {
        val rpcBody = buildJsonObject {
            put("p_season_id", seasonId)
        }

        val response = client.post("$apiUrl/rpc/get_season_payment_report") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(rpcBody)
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            try {
                json.decodeFromString<List<SeasonPaymentReportRow>>(bodyText)
            } catch (e: Exception) {
                println("Error parsing payment report: ${e.message}")
                emptyList()
            }
        } else {
            println("Error getSeasonPaymentReport: ${response.status} - ${response.bodyAsText()}")
            emptyList()
        }
    }
}
