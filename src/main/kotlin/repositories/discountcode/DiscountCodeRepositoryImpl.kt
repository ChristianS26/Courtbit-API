package com.incodap.repositories.discountcode

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import models.discountcode.*
import mu.KotlinLogging
import repositories.discountcode.DiscountCodeRepository

private val logger = KotlinLogging.logger {}

class DiscountCodeRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : DiscountCodeRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun create(code: DiscountCode): DiscountCode? {
        val response = client.post("$apiUrl/discount_codes") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DiscountCode.serializer(), code))
        }
        val body = response.bodyAsText()
        return if (response.status.isSuccess()) {
            val list = json.decodeFromString(ListSerializer(DiscountCode.serializer()), body)
            list.firstOrNull()
        } else {
            logger.warn { "Failed to create discount code: ${response.status} - $body" }
            null
        }
    }

    override suspend fun getByOrganizerId(organizerId: String): List<DiscountCode> {
        val url = "$apiUrl/discount_codes?organization_id=eq.$organizerId&order=created_at.desc&select=*"
        val response = client.get(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        return if (response.status == HttpStatusCode.OK) {
            json.decodeFromString(ListSerializer(DiscountCode.serializer()), response.bodyAsText())
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): DiscountCode? {
        val url = "$apiUrl/discount_codes?id=eq.$id&select=*"
        val response = client.get(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        return if (response.status == HttpStatusCode.OK) {
            val list = json.decodeFromString(ListSerializer(DiscountCode.serializer()), response.bodyAsText())
            list.firstOrNull()
        } else null
    }

    override suspend fun update(id: String, fields: Map<String, JsonElement>): Boolean {
        val url = "$apiUrl/discount_codes?id=eq.$id"
        val response = client.patch(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.JsonObject(fields).toString())
        }
        return response.status.isSuccess()
    }

    override suspend fun delete(id: String): Boolean {
        val url = "$apiUrl/discount_codes?id=eq.$id"
        val response = client.delete(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        return response.status.isSuccess()
    }

    /**
     * PostgREST returns jsonb RPC results in varying formats:
     * - Direct object: {"valid":false,...}
     * - JSON string (double-encoded): "{\"valid\":false,...}"
     * This helper unwraps to the actual JSON string for deserialization.
     */
    private fun unwrapRpcJsonb(raw: String): String {
        val trimmed = raw.trim()
        // If it's a JSON string literal (starts with "), unwrap it
        if (trimmed.startsWith("\"")) {
            return json.decodeFromString<String>(trimmed)
        }
        return trimmed
    }

    override suspend fun getUsagesByOrganizerId(organizerId: String): List<DiscountCodeUsageResponse> {
        val url = "$apiUrl/discount_code_usages_enriched?organization_id=eq.$organizerId&order=used_at.desc&select=*"
        val response = client.get(url) {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
        }
        return if (response.status == HttpStatusCode.OK) {
            json.decodeFromString(ListSerializer(DiscountCodeUsageResponse.serializer()), response.bodyAsText())
        } else {
            logger.warn { "Failed to fetch discount code usages: ${response.status}" }
            emptyList()
        }
    }

    override suspend fun validateCode(
        code: String,
        tournamentId: String,
        playerUid: String
    ): ValidateDiscountCodeResponse {
        val dto = RpcValidateDiscountCodeDto(code, tournamentId, playerUid)
        val response = client.post("$apiUrl/rpc/validate_discount_code") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RpcValidateDiscountCodeDto.serializer(), dto))
        }
        val body = response.bodyAsText()
        logger.info { "RPC validate_discount_code -> ${response.status} | $body" }
        val unwrapped = unwrapRpcJsonb(body)
        return json.decodeFromString(ValidateDiscountCodeResponse.serializer(), unwrapped)
    }

    override suspend fun applyCode(
        code: String, tournamentId: String, playerUid: String, partnerUid: String,
        categoryId: String, playerName: String, restriction: String?,
        usedByEmail: String?, originalAmount: Int?
    ): ValidateDiscountCodeResponse {
        val dto = RpcApplyDiscountCodeDto(
            code, tournamentId, playerUid, partnerUid,
            categoryId, playerName, restriction, usedByEmail, originalAmount
        )
        val response = client.post("$apiUrl/rpc/apply_discount_code") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RpcApplyDiscountCodeDto.serializer(), dto))
        }
        val body = response.bodyAsText()
        logger.info { "RPC apply_discount_code -> ${response.status} | $body" }
        val unwrapped = unwrapRpcJsonb(body)
        return json.decodeFromString(ValidateDiscountCodeResponse.serializer(), unwrapped)
    }
}
