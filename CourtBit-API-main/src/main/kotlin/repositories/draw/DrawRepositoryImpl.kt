package repositories.draw

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.draw.DrawRequest
import models.draw.DrawResponse

class DrawRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DrawRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getDrawsByTournament(tournamentId: String): List<DrawResponse> {
        val response = client.get("$apiUrl/draws") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter(
                "select",
                "id,tournament_id,pdf_url,category:category_id(id,name,position)"
            ) // Join de category usando alias
            parameter("tournament_id", "eq.$tournamentId")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(DrawResponse.serializer()),
                response.bodyAsText()
            )
        } else {
            println("❌ Error getDrawsByTournament: ${response.status}")
            emptyList()
        }
    }

    override suspend fun createDraw(draw: DrawRequest): Boolean {
        return try {
            val url = "$apiUrl/draws"
            val response = client.post(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation") // <-- para ver detalle de error
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(listOf(draw)) // Supabase insert: array de objetos
            }
            val body = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }
            println("🛰️ POST $url -> ${response.status}. Body=$body") // <-- LOG CLAVE
            response.status.isSuccess()
        } catch (e: Exception) {
            println("❗️Excepción al crear draw: ${e.stackTraceToString()}")
            false
        }
    }


    override suspend fun deleteDraw(drawId: String): Boolean {
        val response = client.delete("$apiUrl/draws") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$drawId")
        }
        return response.status.isSuccess()
    }
}
