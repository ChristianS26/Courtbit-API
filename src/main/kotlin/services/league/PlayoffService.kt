package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class PlayoffService(
    private val client: HttpClient,
    private val config: SupabaseConfig
) {
    /**
     * Checks if the regular season is complete (all matchdays 1-5 have scores)
     */
    suspend fun isRegularSeasonComplete(categoryId: String): Result<Boolean> {
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        return try {
            val response = client.post("${config.apiUrl}/rpc/is_regular_season_complete") {
                header("apikey", config.apiKey)
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                val result = response.bodyAsText().toBooleanStrictOrNull() ?: false
                Result.success(result)
            } else {
                Result.failure(IllegalStateException("Failed to check regular season status: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if semifinals are complete (all semifinals matches have scores)
     */
    suspend fun areSemifinalsComplete(categoryId: String): Result<Boolean> {
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        return try {
            val response = client.post("${config.apiUrl}/rpc/are_semifinals_complete") {
                header("apikey", config.apiKey)
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                val result = response.bodyAsText().toBooleanStrictOrNull() ?: false
                Result.success(result)
            } else {
                Result.failure(IllegalStateException("Failed to check semifinals status: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Assigns players to semifinals based on regular season rankings
     * Validates that regular season is complete before assignment
     */
    suspend fun assignSemifinals(categoryId: String): Result<String> {
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        return try {
            val response = client.post("${config.apiUrl}/rpc/assign_semifinals_players") {
                header("apikey", config.apiKey)
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                val result = response.bodyAsText()
                Result.success(result)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(IllegalStateException("Semifinals assignment failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Assigns players to final based on direct qualifiers + semifinals winners
     * Validates that regular season and semifinals are complete before assignment
     */
    suspend fun assignFinal(categoryId: String): Result<String> {
        val payload = buildJsonObject {
            put("p_category_id", categoryId)
        }

        return try {
            val response = client.post("${config.apiUrl}/rpc/assign_final_players_validated") {
                header("apikey", config.apiKey)
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                val result = response.bodyAsText()
                Result.success(result)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(IllegalStateException("Final assignment failed: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets the playoff status for a category
     * Returns whether regular season and semifinals are complete
     */
    suspend fun getPlayoffStatus(categoryId: String): Result<PlayoffStatusResult> {
        return try {
            val regularSeasonResult = isRegularSeasonComplete(categoryId)
            val semifinalsResult = areSemifinalsComplete(categoryId)

            if (regularSeasonResult.isFailure || semifinalsResult.isFailure) {
                return Result.failure(
                    IllegalStateException("Failed to get playoff status")
                )
            }

            val status = PlayoffStatusResult(
                regularSeasonComplete = regularSeasonResult.getOrDefault(false),
                semifinalsComplete = semifinalsResult.getOrDefault(false),
                canAssignSemifinals = regularSeasonResult.getOrDefault(false),
                canAssignFinal = regularSeasonResult.getOrDefault(false) && semifinalsResult.getOrDefault(false)
            )

            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Result object for playoff status
 */
data class PlayoffStatusResult(
    val regularSeasonComplete: Boolean,
    val semifinalsComplete: Boolean,
    val canAssignSemifinals: Boolean,
    val canAssignFinal: Boolean
)
