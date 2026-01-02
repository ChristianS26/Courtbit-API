package repositories.tournament

import com.incodap.config.SupabaseConfig
import com.incodap.models.tournament.UpdateTournamentRequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.category.SetTournamentCategoriesPayload
import models.tournament.CreateTournamentRequest
import models.tournament.TournamentResponse

class TournamentRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : TournamentRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<TournamentResponse> {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(TournamentResponse.serializer()),
                response.bodyAsText()
            )
        } else {
            println("❌ Error getAll: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getByOrganizerId(organizerId: String): List<TournamentResponse> {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*")
            parameter("organizer_id", "eq.$organizerId")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(TournamentResponse.serializer()),
                response.bodyAsText()
            )
        } else {
            println("❌ Error getByOrganizerId: ${response.status}")
            emptyList()
        }
    }

    override suspend fun getById(id: String): TournamentResponse? {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(TournamentResponse.serializer()),
                response.bodyAsText()
            ).firstOrNull()
        } else {
            println("❌ Error getTournamentById: ${response.status}")
            null
        }
    }

    override suspend fun patchField(id: String, fields: Map<String, Any>, patchType: String): Boolean {
        return try {
            val jsonPayload = buildJsonObject {
                fields.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> put(key, value)
                        is String -> put(key, value)
                        is Number -> put(key, value.toString())
                        else -> error("Tipo no soportado para PATCH: ${value.javaClass.simpleName}")
                    }
                }
            }

            // Supabase espera un array de objetos
            val arrayJson = "[${jsonPayload.toString()}]"

            val url = "$apiUrl/tournaments?id=eq.$id"
            println("PATCH (micro): PATCH a $url con body $arrayJson")

            val response = client.patch(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(arrayJson)
            }
            val responseBody = try {
                response.bodyAsText()
            } catch (e: Exception) {
                "No se pudo leer el body: ${e.message}"
            }
            println("PATCH (micro): status=${response.status} body=$responseBody")
            response.status.isSuccess()
        } catch (e: Exception) {
            println("PATCH (micro): Exception lanzada al hacer PATCH: ${e.stackTraceToString()}")
            false
        }
    }

    override suspend fun create(request: CreateTournamentRequest): TournamentResponse? {
        val url = "$apiUrl/tournaments"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(request)) // Supabase espera array
        }

        val status = response.status
        val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }
        println("🛰️ Supabase POST $url -> ${status.value} ${status.description}\nBody: $bodyText")

        if (!status.isSuccess()) return null

        return bodyText.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<List<TournamentResponse>>(it).firstOrNull()
        }
    }

    override suspend fun update(id: String, request: UpdateTournamentRequest): Boolean {
        val response = client.patch("$apiUrl/tournaments?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/tournaments?id=eq.$id"

        return try {
            val response = client.delete(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
            }

            val status = response.status
            val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }

            println("🗑️ Supabase DELETE $url -> ${status.value} ${status.description}")
            println("🗑️ Supabase DELETE body: $bodyText")

            if (status.isSuccess()) {
                true
            } else {
                // Intentamos parsear error de Supabase para detectar caso de pagos
                val error = runCatching { json.decodeFromString<SupabaseError>(bodyText) }.getOrNull()

                if (error?.code == "23502" && error.message?.contains("tournament_id") == true) {
                    // Caso específico: NOT NULL en tournament_id de payments
                    throw TournamentHasPaymentsException(
                        error.message ?: "No se puede eliminar el torneo porque tiene pagos registrados."
                    )
                }

                false
            }
        } catch (e: TournamentHasPaymentsException) {
            // se repropaga para que el service lo maneje
            throw e
        } catch (e: Exception) {
            println("🧨 Supabase DELETE exception para torneo $id: ${e.stackTraceToString()}")
            false
        }
    }

    override suspend fun setTournamentCategories(
        tournamentId: String,
        categoryIds: List<Int>
    ): Result<Unit> {
        val payload = SetTournamentCategoriesPayload(
            p_tournament_id = tournamentId,
            p_new_categories = categoryIds
        )

        val url = "$apiUrl/rpc/set_tournament_categories"
        val response = client.post(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val text = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }
        println("RPC set_tournament_categories => ${response.status}\nBody: $text")

        return if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("RPC failed: ${response.status}. $text"))
        }
    }

    override suspend fun updateFlyerUrl(id: String, flyerUrl: String): Boolean {
        val response = client.patch("$apiUrl/tournaments?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf("flyer_url" to flyerUrl))
        }

        return response.status.isSuccess()
    }

    override suspend fun updateClubLogoUrl(id: String, logoUrl: String): Boolean {
        val response = client.patch("$apiUrl/tournaments?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(mapOf("club_logo_url" to logoUrl))
        }
        return response.status.isSuccess()
    }
}

@Serializable
data class SupabaseError(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null,
)

class TournamentHasPaymentsException(message: String? = null) : RuntimeException(message)
