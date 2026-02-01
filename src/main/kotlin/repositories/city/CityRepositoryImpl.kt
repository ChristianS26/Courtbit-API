package repositories.city

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.city.CityResponseDto

class CityRepositoryImpl(
    private val client: HttpClient,
    private val config: SupabaseConfig,
    private val json: Json
) : CityRepository {

    override suspend fun getAll(): List<CityResponseDto> {
        val response = client.get("${config.apiUrl}/cities") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("select", "*")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(CityResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }

    override suspend fun getById(id: Int): CityResponseDto? {
        val response = client.get("${config.apiUrl}/cities") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("id", "eq.$id")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val list = json.decodeFromString(
                ListSerializer(CityResponseDto.serializer()),
                response.bodyAsText()
            )
            list.firstOrNull()
        } else null
    }
}
