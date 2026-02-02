package repositories

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import models.ShirtSizeResponse

interface ShirtSizeRepository {
    suspend fun getAll(): List<ShirtSizeResponse>
    suspend fun getByGenderStyle(genderStyle: String): List<ShirtSizeResponse>
    suspend fun getSeasonShirtSizes(seasonId: String): List<ShirtSizeResponse>
    suspend fun setSeasonShirtSizes(seasonId: String, shirtSizeIds: List<String>): Boolean
}

class ShirtSizeRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : ShirtSizeRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<ShirtSizeResponse> {
        val response = client.get("$apiUrl/shirt_sizes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("is_active", "eq.true")
            parameter("order", "gender_style,sort_order")
            parameter("select", "id,size_code,display_name,gender_style,sort_order")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<ShirtSizeResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getByGenderStyle(genderStyle: String): List<ShirtSizeResponse> {
        val response = client.get("$apiUrl/shirt_sizes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("is_active", "eq.true")
            parameter("gender_style", "eq.$genderStyle")
            parameter("order", "sort_order")
            parameter("select", "id,size_code,display_name,gender_style,sort_order")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            json.decodeFromString<List<ShirtSizeResponse>>(bodyText)
        } else {
            emptyList()
        }
    }

    override suspend fun getSeasonShirtSizes(seasonId: String): List<ShirtSizeResponse> {
        // Get shirt sizes configured for this season via join
        val response = client.get("$apiUrl/season_shirt_sizes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
            parameter("is_active", "eq.true")
            parameter("select", "shirt_sizes(id,size_code,display_name,gender_style,sort_order)")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val bodyText = response.bodyAsText()
        // Parse the nested response
        @kotlinx.serialization.Serializable
        data class SeasonShirtSizeJoin(
            val shirt_sizes: ShirtSizeResponse?
        )

        return try {
            val joins = json.decodeFromString<List<SeasonShirtSizeJoin>>(bodyText)
            joins.mapNotNull { it.shirt_sizes }.sortedWith(
                compareBy({ it.genderStyle }, { it.sortOrder })
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun setSeasonShirtSizes(seasonId: String, shirtSizeIds: List<String>): Boolean {
        // First, delete existing configuration
        val deleteResponse = client.delete("$apiUrl/season_shirt_sizes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("season_id", "eq.$seasonId")
        }

        if (!deleteResponse.status.isSuccess() && deleteResponse.status != HttpStatusCode.NoContent) {
            return false
        }

        // If no sizes to add, we're done
        if (shirtSizeIds.isEmpty()) {
            return true
        }

        // Insert new configuration
        @kotlinx.serialization.Serializable
        data class InsertRow(
            val season_id: String,
            val shirt_size_id: String,
            val is_active: Boolean = true
        )

        val rows = shirtSizeIds.map { InsertRow(seasonId, it) }
        val insertBody = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(InsertRow.serializer()), rows)

        val insertResponse = client.post("$apiUrl/season_shirt_sizes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Content-Type", "application/json")
            header("Prefer", "return=minimal")
            setBody(insertBody)
        }

        return insertResponse.status.isSuccess() || insertResponse.status == HttpStatusCode.Created
    }
}
