package com.incodap.repositories.registrationcode

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.registrationcode.RegistrationCode
import models.registrationcode.RegistrationCodePatch
import models.registrationcode.RegistrationCodeWithTournamentInfo
import repositories.registrationcode.RegistrationCodeRepository
import java.time.Instant

class RegistrationCodeRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : RegistrationCodeRepository {

    override suspend fun createCode(email: String, organizerId: String?): String {
        val code = generateFriendlyCode()

        val payload = buildMap {
            put("code", code)
            put("created_by_email", email)
            organizerId?.let { put("organizer_id", it) }
        }

        val response = client.post("${config.apiUrl}/registration_codes") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(payload))
        }

        if (!response.status.isSuccess()) {
            throw RuntimeException("Error al crear código de registro")
        }

        return code
    }


    override suspend fun getValidCode(code: String): RegistrationCode? {
        val url = "${config.apiUrl}/registration_codes?code=eq.$code&used=is.false&select=*"
        val response = client.get(url) {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }

        return if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val codes = json.decodeFromString(ListSerializer(RegistrationCode.serializer()), body)
            codes.firstOrNull()
        } else {
            null
        }
    }

    override suspend fun markCodeAsUsed(code: String, usedByEmail: String, tournamentId: String): Boolean {
        val url = "${config.apiUrl}/registration_codes?code=eq.$code"
        val patchBody = RegistrationCodePatch(
            used = true,
            used_by_email = usedByEmail,
            used_at = Instant.now().toString(),
            used_in_tournament_id = tournamentId
        )
        val response = client.patch(url) {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(patchBody)
        }
        return response.status == HttpStatusCode.NoContent
    }


    override suspend fun getAllCodes(): List<RegistrationCode> {
        val url = "${config.apiUrl}/registration_codes?select=*"
        val response = client.get(url) {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }
        return if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            json.decodeFromString(ListSerializer(RegistrationCode.serializer()), body)
        } else {
            emptyList()
        }
    }

    override suspend fun getCodesByOrganizerId(organizerId: String): List<RegistrationCode> {
        val url = "${config.apiUrl}/registration_codes?select=*&organizer_id=eq.$organizerId"
        val response = client.get(url) {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }
        return if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            json.decodeFromString(ListSerializer(RegistrationCode.serializer()), body)
        } else {
            emptyList()
        }
    }

    override suspend fun getAllCodesWithTournamentInfo(): List<RegistrationCodeWithTournamentInfo> {
        val url = "${config.apiUrl}/registration_codes?select=*,tournaments:tournaments!used_in_tournament_id(name)&order=used.desc,used_at.desc"
        val response = client.get(url) {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }
        return if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val codes = json.decodeFromString(ListSerializer(RegistrationCodeWithTournamentInfo.serializer()), body)

            // --- LOG PARA VALIDAR NOMBRES DE TORNEOS ---
            codes.forEach { code ->
            }
            // ------------------------------------------

            codes
        } else {
            emptyList()
        }
    }

    private fun generateFriendlyCode(length: Int = 8): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..length).map { chars.random() }.joinToString("")
    }
}
