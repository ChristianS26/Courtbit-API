package utils

import org.slf4j.LoggerFactory
import java.util.Base64

object SupabaseKeyChecker {
    private val log = LoggerFactory.getLogger(SupabaseKeyChecker::class.java)
    fun logRoleFromKey(key: String) {
        runCatching {
            val parts = key.split('.')
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            log.info("🔐 SUPABASE key payload: {}", payload) // busca "role":"service_role"
        }.onFailure { log.warn("No se pudo decodificar SUPABASE_API_KEY: {}", it.message) }
    }
}
