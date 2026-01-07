package com.incodap.repositories.users

import com.incodap.config.SupabaseConfig
import com.incodap.models.users.UserDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.profile.UpdateProfileRequest
import models.users.DeleteUserResult
import org.slf4j.LoggerFactory

class UserRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : UserRepository {

    private val apiUrl = config.apiUrl        // debería incluir /rest/v1
    private val apiKey = config.apiKey
    private val logger = LoggerFactory.getLogger(UserRepositoryImpl::class.java)

    // Para parsear errores estándar de PostgREST/Supabase
    @Serializable
    private data class SupabaseError(
        val message: String? = null,
        val details: String? = null,
        val hint: String? = null,
        val code: String? = null
    ) {
        override fun toString(): String = "message=$message details=$details hint=$hint code=$code"
    }

    // -------------------------------------------------------
    // 🔍 Búsquedas
    // -------------------------------------------------------

    override suspend fun searchUsers(query: String): List<UserDto> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val safe = q.replace("%", "\\%").replace("_", "\\_")
        val like = "%$safe%"
        val filter = "(first_name.ilike.$like,last_name.ilike.$like,email.ilike.$like)"

        return try {
            val response = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("or", filter)
                parameter("select", "*")
                parameter("limit", 10)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                json.decodeFromString(ListSerializer(UserDto.serializer()), body)
            } else {
                logger.warn("⚠️ searchUsers failed: status={} body={}", response.status, body)
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("❌ Error in searchUsers: {}", e.message, e)
            emptyList()
        }
    }

    // -------------------------------------------------------
    // 🔎 Buscar por email / UID
    // -------------------------------------------------------

    override suspend fun findByEmail(email: String): UserDto? {
        val normalizedEmail = email.trim().lowercase()
        return try {
            val response = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("email", "eq.$normalizedEmail")
                parameter("select", "*")
                parameter("limit", 1)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                json.decodeFromString(ListSerializer(UserDto.serializer()), body).firstOrNull()
            } else {
                logger.warn("⚠️ findByEmail failed: status={} body={}", response.status, body)
                null
            }
        } catch (e: Exception) {
            logger.error("❌ Error al buscar por email: {}", e.message, e)
            null
        }
    }

    override suspend fun findByUid(uid: String): UserDto? {
        return try {
            val response = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("uid", "eq.$uid")
                parameter("select", "*")
                parameter("limit", 1)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                json.decodeFromString(ListSerializer(UserDto.serializer()), body).firstOrNull()
            } else {
                logger.warn("⚠️ findByUid failed: status={} body={}", response.status, body)
                null
            }
        } catch (e: Exception) {
            logger.error("❌ Error al buscar por UID: {}", e.message, e)
            null
        }
    }

    // -------------------------------------------------------
    // 🧾 Inserción y actualización de datos
    // -------------------------------------------------------

    override suspend fun insertUser(userDto: UserDto): Boolean {
        return try {
            val payload = listOf(
                buildJsonObject {
                    put("uid", userDto.uid)
                    put("email", userDto.email)
                    put("password_hash", userDto.passwordHash)
                    put("first_name", userDto.firstName)
                    put("last_name", userDto.lastName)
                    userDto.phone?.let { put("phone", it) }
                    userDto.gender?.let { put("gender", it) }
                    userDto.birthdate?.let { put("birthdate", it) }
                    put("role", userDto.role)
                    put("photo_url", userDto.photoUrl ?: "")
                    userDto.countryIso?.let { put("country_iso", it) }
                    userDto.shirtSize?.let { put("shirt_size", it) }
                }
            )

            val response = client.post("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            val status = response.status
            val bodyText = response.bodyAsText()
            logger.info("📝 INSERT /users → status={} body={}", status, bodyText)

            val err = runCatching { json.decodeFromString(SupabaseError.serializer(), bodyText) }.getOrNull()

            when {
                // Éxito
                status == HttpStatusCode.Created || status == HttpStatusCode.OK -> true

                // 409 duplicado (unique email)
                status == HttpStatusCode.Conflict ||
                        err?.code == "23505" ||
                        (err?.message?.contains("duplicate", ignoreCase = true) == true) ||
                        (err?.message?.contains("unique", ignoreCase = true) == true) -> {
                    logger.warn("⚠️ INSERT users conflict (duplicate): {}", err ?: bodyText)
                    throw IllegalArgumentException("Email ya registrado.")
                }

                // Permisos / RLS
                status == HttpStatusCode.Unauthorized ||
                        status == HttpStatusCode.Forbidden ||
                        err?.code == "42501" ||
                        (err?.message?.contains("permission denied", ignoreCase = true) == true) ||
                        (err?.message?.contains("row level security", ignoreCase = true) == true) ||
                        (err?.message?.contains("rls", ignoreCase = true) == true) -> {
                    logger.error("❌ INSERT users denied (RLS/Key): {}", err ?: bodyText)
                    throw IllegalStateException("Acceso denegado por la base de datos. Revisa service_role o policies.")
                }

                // NOT NULL / tipos
                status == HttpStatusCode.BadRequest ||
                        err?.code == "23502" || // not_null_violation
                        err?.code == "22P02"    // invalid_text_representation
                    -> {
                    logger.error("❌ INSERT users bad request/constraint: {}", err ?: bodyText)
                    throw IllegalArgumentException(err?.message ?: "Datos inválidos para crear usuario.")
                }

                // Caso no mapeado → false (para que AuthService lo trate)
                else -> {
                    logger.error("❌ INSERT users fallo no mapeado. status={} body={}", status, bodyText)
                    false
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            logger.error("❌ Error al insertar usuario (exception): {}", e.message, e)
            false
        }
    }


    override suspend fun updateProfile(uid: String, request: UpdateProfileRequest): UserDto? {
        return try {
            val jsonBody = buildJsonObject {
                request.firstName?.let { put("first_name", it) }
                request.lastName?.let { put("last_name", it) }
                request.phone?.let { put("phone", it) }
                request.gender?.let { put("gender", it) }
                request.photoUrl?.let { put("photo_url", it) }
                request.countryIso?.let { put("country_iso", it) }
                request.shirtSize?.let { put("shirt_size", it) }
            }

            val response = client.patch("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                parameter("uid", "eq.$uid")
                setBody(jsonBody)
            }

            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                response.body<List<UserDto>>().firstOrNull()
            } else {
                logger.warn("⚠️ updateProfile failed: status={} body={}", response.status, body)
                null
            }
        } catch (e: Exception) {
            logger.error("❌ Error actualizando perfil: {}", e.message, e)
            null
        }
    }

    override suspend fun updateProfilePhoto(uid: String, photoUrl: String): Boolean {
        return try {
            val response = client.patch("$apiUrl/users?uid=eq.$uid") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(mapOf("photo_url" to photoUrl))
            }
            val body = response.bodyAsText()
            val ok = response.status.isSuccess()
            if (!ok) logger.warn("⚠️ updateProfilePhoto failed: status={} body={}", response.status, body)
            ok
        } catch (e: Exception) {
            logger.error("❌ Error al actualizar foto: {}", e.message, e)
            false
        }
    }

    override suspend fun updatePassword(uid: String, newHash: String): Boolean {
        return try {
            val response = client.patch("$apiUrl/users?uid=eq.$uid") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(mapOf("password_hash" to newHash))
            }
            val body = response.bodyAsText()
            val ok = response.status.isSuccess()
            if (!ok) logger.warn("⚠️ updatePassword failed: status={} body={}", response.status, body)
            ok
        } catch (e: Exception) {
            logger.error("❌ Error al actualizar contraseña: {}", e.message, e)
            false
        }
    }

    // -------------------------------------------------------
    // 💳 Stripe Integration
    // -------------------------------------------------------

    override suspend fun getStripeCustomerIdByUid(uid: String): String? {
        @Serializable
        data class StripeIdRow(@SerialName("stripe_customer_id") val stripeCustomerId: String? = null)

        return try {
            val response = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("uid", "eq.$uid")
                parameter("select", "stripe_customer_id")
                parameter("limit", 1)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                val rows = json.decodeFromString(ListSerializer(StripeIdRow.serializer()), body)
                val id = rows.firstOrNull()?.stripeCustomerId
                logger.info("💳 [UserRepo] getStripeCustomerIdByUid({}) -> {}", uid, id)
                id
            } else {
                logger.warn("⚠️ [UserRepo] getStripeCustomerIdByUid failed: status={} body={}", response.status, body)
                null
            }
        } catch (e: Exception) {
            logger.error("❌ [UserRepo] getStripeCustomerIdByUid error: {}", e.message, e)
            null
        }
    }

    override suspend fun updateStripeCustomerId(uid: String, customerId: String): Boolean {
        return try {
            val response = client.patch("$apiUrl/users?uid=eq.$uid") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(mapOf("stripe_customer_id" to customerId))
            }
            val body = response.bodyAsText()
            val ok = response.status.isSuccess()
            if (!ok) logger.warn("⚠️ updateStripeCustomerId failed: status={} body={}", response.status, body)
            ok
        } catch (e: Exception) {
            logger.error("❌ Error actualizando Stripe CustomerId: {}", e.message, e)
            false
        }
    }

    // -------------------------------------------------------
    // 🗑️ Delete User
    // -------------------------------------------------------

    override suspend fun deleteByUid(uid: String): DeleteUserResult {
        return try {
            val deleteResponse = client.delete("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                parameter("uid", "eq.$uid")
            }

            val body = deleteResponse.bodyAsText()

            if (!deleteResponse.status.isSuccess()) {
                logger.warn("⚠️ deleteByUid failed: status={} body={}", deleteResponse.status, body)
                return DeleteUserResult.Error("Database error")
            }

            // Caso ideal: body trae filas borradas
            if (body.isNotBlank()) {
                val deletedRows = json.decodeFromString(ListSerializer(UserDto.serializer()), body)
                return if (deletedRows.isEmpty()) DeleteUserResult.NotFound else DeleteUserResult.Deleted
            }

            // Si el body viene vacío, confirmamos con un SELECT (única forma segura)
            val stillExists = findByUid(uid) != null
            if (stillExists) DeleteUserResult.NotFound else DeleteUserResult.Deleted

        } catch (e: Exception) {
            logger.error("❌ Error deleting user: {}", e.message, e)
            DeleteUserResult.Error(e.message ?: "Unknown error")
        }
    }
}
