package com.incodap.repositories.payments

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import models.payments.PaymentReportRowDto
import models.payments.RpcApplyCodeDto
import models.payments.RpcApplyStripePaymentDto
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class PaymentRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : PaymentRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun applyStripePayment(dto: RpcApplyStripePaymentDto): Boolean {
        val response = client.post("$apiUrl/rpc/apply_stripe_payment") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }
        val raw = response.bodyAsText().trim()
        logger.info { "RPC apply_stripe_payment -> ${response.status} | $raw" }
        if (!response.status.isSuccess()) return false

        return try {
            if (raw.equals("true", true)) return true
            if (raw.equals("false", true)) return false
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw)
            when {
                json is kotlinx.serialization.json.JsonObject && json["apply_stripe_payment"] != null ->
                    json["apply_stripe_payment"]!!.jsonPrimitive.boolean
                json is kotlinx.serialization.json.JsonArray &&
                        json.firstOrNull() is kotlinx.serialization.json.JsonObject &&
                        (json.first() as kotlinx.serialization.json.JsonObject)["apply_stripe_payment"] != null ->
                    (json.first() as kotlinx.serialization.json.JsonObject)["apply_stripe_payment"]!!.jsonPrimitive.boolean
                else -> false
            }
        } catch (_: Exception) { false }
    }

    // PaymentRepositoryImpl.kt
    override suspend fun applyRegistrationCode(dto: RpcApplyCodeDto): Boolean {
        val response = client.post("$apiUrl/rpc/apply_registration_code") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(dto)
        }

        val raw = response.bodyAsText().trim()
        logger.info { "RPC apply_registration_code -> ${response.status} | $raw" }

        if (!response.status.isSuccess()) return false

        // Intenta convertir el cuerpo a boolean sin importar el formato.
        return try {
            // 1) respuesta simple: true/false
            if (raw.equals("true", true)) return true
            if (raw.equals("false", true)) return false

            // 2) JSON: {"apply_registration_code":true} o [{"apply_registration_code":true}]
            val json = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(raw)

            when {
                json is kotlinx.serialization.json.JsonObject &&
                        json["apply_registration_code"] != null ->
                    json["apply_registration_code"]!!.jsonPrimitive.boolean

                json is kotlinx.serialization.json.JsonArray &&
                        json.firstOrNull() is kotlinx.serialization.json.JsonObject &&
                        (json.first() as kotlinx.serialization.json.JsonObject)["apply_registration_code"] != null ->
                    (json.first() as kotlinx.serialization.json.JsonObject)["apply_registration_code"]!!
                        .jsonPrimitive.boolean

                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }


    override suspend fun isWebhookProcessed(eventId: String): Boolean {
        return try {
            val response = client.get("$apiUrl/rest/v1/processed_webhook_events") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("event_id", "eq.$eventId")
                parameter("select", "event_id")
            }
            if (!response.status.isSuccess()) return false
            val body = response.bodyAsText().trim()
            // Empty array means not processed
            body != "[]"
        } catch (e: Exception) {
            logger.error(e) { "Error checking webhook idempotency for $eventId" }
            false
        }
    }

    override suspend fun markWebhookProcessed(eventId: String, eventType: String): Boolean {
        return try {
            val payload = buildJsonObject {
                put("event_id", eventId)
                put("event_type", eventType)
            }
            val response = client.post("$apiUrl/rest/v1/processed_webhook_events") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "Error marking webhook $eventId as processed" }
            false
        }
    }

    override suspend fun getPaymentsReport(tournamentId: String): List<PaymentReportRowDto> {
        val payload = buildJsonObject { put("p_tournament_id", tournamentId) }
        val response = client.post("$apiUrl/rpc/get_payments_report") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "❌ RPC get_payments_report failed: ${response.status} | $body" }
            return emptyList()
        }
        return json.decodeFromString(ListSerializer(PaymentReportRowDto.serializer()), body)
    }
}
