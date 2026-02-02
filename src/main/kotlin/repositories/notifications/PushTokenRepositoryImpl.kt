// repositories/notifications/PushTokenRepositoryImpl.kt
package repositories.notifications

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.http.isSuccess
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PushTokenRepositoryImpl(
    private val client: HttpClient,
    @Suppress("UNUSED_PARAMETER") private val json: Json, // se inyecta para seguir el patrón, útil si luego decodificas
    private val config: SupabaseConfig
) : PushTokenRepository {

    private val apiUrl = "${config.apiUrl}/push_tokens"
    private val apiKey = config.apiKey // ⚠️ Debe ser Service Role en backend

    @Serializable
    private data class PushTokenRow(
        val user_id: String,
        val registration_token: String,
        val platform: String,
        val device_id: String? = null,
        val flavor: String,
        val is_active: Boolean = true,
        val last_seen_at: String,
    )

    override suspend fun upsertToken(
        userId: String,
        token: String,
        platform: String,
        deviceId: String?,
        flavor: String
    ) {
        val nowIso = OffsetDateTime.now(ZoneOffset.UTC).toString()

        // 1) Desactivar otros tokens activos del mismo device para este usuario (soft)
        if (!deviceId.isNullOrBlank()) {
            val resp = client.patch(apiUrl) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                parameter("user_id", "eq.$userId")
                parameter("device_id", "eq.$deviceId")
                setBody(mapOf("is_active" to false))
            }
            if (!resp.status.isSuccess()) {
                val body = runCatching { resp.bodyAsText() }.getOrDefault("(sin body)")
            }
        }

        // 2) Upsert por registration_token (único)
        val row = PushTokenRow(
            user_id = userId,
            registration_token = token,
            platform = platform,
            device_id = deviceId,
            flavor = flavor,
            is_active = true,
            last_seen_at = nowIso
        )

        val insert = client.post(apiUrl) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            // Supabase espera array para insert
            parameter("on_conflict", "registration_token")
            setBody(listOf(row))
        }

        if (!insert.status.isSuccess()) {
            val body = runCatching { insert.bodyAsText() }.getOrDefault("(sin body)")
            error("Supabase upsertToken failed: ${insert.status} -> $body")
        }

        // 3) Asegurar activo + last_seen (si ya existía)
        val patch = client.patch(apiUrl) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("registration_token", "eq.$token") // usa parameter() para escapar caracteres del token
            setBody(mapOf("is_active" to true, "last_seen_at" to nowIso))
        }
        if (!patch.status.isSuccess()) {
            val body = runCatching { patch.bodyAsText() }.getOrDefault("(sin body)")
        }
    }

    override suspend fun deleteToken(userId: String, token: String) {
        // Soft delete (auditable)
        val resp = client.patch(apiUrl) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("user_id", "eq.$userId")
            parameter("registration_token", "eq.$token")
            setBody(mapOf("is_active" to false))
        }
        if (!resp.status.isSuccess()) {
            val body = runCatching { resp.bodyAsText() }.getOrDefault("(sin body)")
            error("Supabase deleteToken failed: ${resp.status} -> $body")
        }
    }
}
