package repositories.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.league.CreateAdjustmentRequest
import models.league.LeagueAdjustmentResponse
import models.league.UpdateAdjustmentRequest

class AdjustmentRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : AdjustmentRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getByCategory(categoryId: String): List<LeagueAdjustmentResponse> {
        return try {
            val response = client.get("$apiUrl/league_adjustments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("category_id", "eq.$categoryId")
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString(
                    ListSerializer(LeagueAdjustmentResponse.serializer()),
                    response.bodyAsText()
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getById(id: String): LeagueAdjustmentResponse? {
        return try {
            val response = client.get("$apiUrl/league_adjustments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("id", "eq.$id")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<LeagueAdjustmentResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun create(
        request: CreateAdjustmentRequest,
        createdByUid: String
    ): LeagueAdjustmentResponse? {
        return try {
            @Serializable
            data class CreatePayload(
                val player_id: String,
                val category_id: String,
                val season_id: String,
                val value: Int,
                val reason: String,
                val created_by_uid: String,
                val points_for_adj: Int? = null,
                val points_against_adj: Int? = null,
                val games_won_adj: Int? = null,
                val games_lost_adj: Int? = null
            )

            val payload = CreatePayload(
                player_id = request.playerId,
                category_id = request.categoryId,
                season_id = request.seasonId,
                value = request.value,
                reason = request.reason,
                created_by_uid = createdByUid,
                points_for_adj = request.pointsForAdj,
                points_against_adj = request.pointsAgainstAdj,
                games_won_adj = request.gamesWonAdj,
                games_lost_adj = request.gamesLostAdj
            )

            val response = client.post("$apiUrl/league_adjustments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(payload))
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<LeagueAdjustmentResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun update(id: String, request: UpdateAdjustmentRequest): Boolean {
        return try {
            @Serializable
            data class UpdatePayload(
                val value: Int? = null,
                val reason: String? = null,
                val points_for_adj: Int? = null,
                val points_against_adj: Int? = null,
                val games_won_adj: Int? = null,
                val games_lost_adj: Int? = null
            )

            val payload = UpdatePayload(
                value = request.value,
                reason = request.reason,
                points_for_adj = request.pointsForAdj,
                points_against_adj = request.pointsAgainstAdj,
                games_won_adj = request.gamesWonAdj,
                games_lost_adj = request.gamesLostAdj
            )

            val response = client.patch("$apiUrl/league_adjustments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                parameter("id", "eq.$id")
                setBody(payload)
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun delete(id: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/league_adjustments") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("id", "eq.$id")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }
}
