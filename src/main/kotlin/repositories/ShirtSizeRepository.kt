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
            println("❌ Error fetching shirt sizes: ${response.status}")
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
            println("❌ Error fetching shirt sizes for $genderStyle: ${response.status}")
            emptyList()
        }
    }
}
