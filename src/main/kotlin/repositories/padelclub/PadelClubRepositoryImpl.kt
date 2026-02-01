package repositories.padelclub

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.padelclub.PadelClubResponseDto

class PadelClubRepositoryImpl(
    private val client: HttpClient,
    private val config: SupabaseConfig,
    private val json: Json
) : PadelClubRepository {

    override suspend fun getAll(): List<PadelClubResponseDto> {
        val response = client.get("${config.apiUrl}/padel_clubs") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("select", "*")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(PadelClubResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }

    override suspend fun getById(id: Int): PadelClubResponseDto? {
        val response = client.get("${config.apiUrl}/padel_clubs") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("id", "eq.$id")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val list = json.decodeFromString(
                ListSerializer(PadelClubResponseDto.serializer()),
                response.bodyAsText()
            )
            list.firstOrNull()
        } else null
    }

    override suspend fun getByCityId(cityId: Int): List<PadelClubResponseDto> {
        val response = client.get("${config.apiUrl}/padel_clubs") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("city_id", "eq.$cityId")
            parameter("select", "*")
            parameter("order", "name.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(PadelClubResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }
}
