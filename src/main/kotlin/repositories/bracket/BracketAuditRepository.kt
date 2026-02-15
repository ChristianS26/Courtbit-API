package repositories.bracket

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Repository for bracket audit log entries.
 * Logs organizer actions for traceability.
 */
class BracketAuditRepository(
    private val client: HttpClient,
    config: SupabaseConfig
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    /**
     * Log an audit entry. Fire-and-forget — failures are non-critical.
     */
    suspend fun log(
        entityType: String,
        entityId: String,
        action: String,
        userId: String?,
        changes: Map<String, Any?>? = null
    ) {
        try {
            val changesJson = changes?.let {
                JsonObject(it.mapValues { (_, v) ->
                    when (v) {
                        null -> JsonNull
                        is String -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        is JsonElement -> v
                        else -> JsonPrimitive(v.toString())
                    }
                })
            }

            val body = buildString {
                append("""{"entity_type":"$entityType","entity_id":"$entityId","action":"$action"""")
                if (userId != null) append(""","user_id":"$userId"""")
                if (changesJson != null) append(""","changes":$changesJson""")
                append("}")
            }

            client.post("$apiUrl/bracket_audit_log") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (_: Exception) {
            // Audit logging is non-critical — never block the main operation
        }
    }
}
