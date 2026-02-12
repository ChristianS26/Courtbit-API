package com.incodap.repositories.discountcode

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
        return if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val list = json.decodeFromString(ListSerializer(DiscountCode.serializer()), body)
            list.firstOrNull()
        } else {
            logger.warn { "Failed to create discount code: ${response.status} - ${response.bodyAsText()}" }
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
        return json.decodeFromString(ValidateDiscountCodeResponse.serializer(), body)
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
        return json.decodeFromString(ValidateDiscountCodeResponse.serializer(), body)
    }
}
