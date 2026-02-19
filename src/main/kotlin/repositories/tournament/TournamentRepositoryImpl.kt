package repositories.tournament

import com.incodap.config.SupabaseConfig
import com.incodap.models.tournament.UpdateTournamentRequest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
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
            parameter("select", "*,organizers!organizer_id(name,logo_url)")
            parameter("deleted_at", "is.null")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<TournamentRawResponse>>(bodyText)
            rawList.map { it.toTournamentResponse() }
        } else {
            emptyList()
        }
    }

    override suspend fun getByOrganizerId(organizerId: String): List<TournamentResponse> {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,organizers!organizer_id(name,logo_url)")
            parameter("organizer_id", "eq.$organizerId")
            parameter("deleted_at", "is.null")
            parameter("order", "start_date.desc")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<TournamentRawResponse>>(bodyText)
            rawList.map { it.toTournamentResponse() }
        } else {
            emptyList()
        }
    }

    override suspend fun getById(id: String): TournamentResponse? {
        val response = client.get("$apiUrl/tournaments") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("id", "eq.$id")
            parameter("deleted_at", "is.null")
            parameter("select", "*,organizers!organizer_id(name,logo_url)")
        }

        return if (response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            val rawList = json.decodeFromString<List<TournamentRawResponse>>(bodyText)
            rawList.firstOrNull()?.toTournamentResponse()
        } else {
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
            response.status.isSuccess()
        } catch (e: Exception) {
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
        // Soft delete via RPC — sets deleted_at instead of removing data
        val url = "$apiUrl/rpc/delete_tournament_cascade"

        return try {
            val response = client.post(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("p_tournament_id" to id))
            }

            val status = response.status
            val bodyText = runCatching { response.bodyAsText() }.getOrElse { "(sin body)" }

            if (status.isSuccess()) {
                val result = bodyText.trim().removeSurrounding("\"")
                result == "deleted"
            } else {
                false
            }
        } catch (e: Exception) {
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

        return if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("RPC failed: ${response.status}. $text"))
        }
    }

    override suspend fun setCategoryPrices(
        tournamentId: String,
        categoryPrices: Map<Int, Int>
    ): Boolean {
        if (categoryPrices.isEmpty()) return true

        // Update each category price individually
        var allSuccess = true
        for ((categoryId, price) in categoryPrices) {
            val url = "$apiUrl/tournament_categories?tournament_id=eq.$tournamentId&category_id=eq.$categoryId"
            val response = client.patch(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("price" to price))
            }

            if (!response.status.isSuccess()) {
                allSuccess = false
            }
        }

        return allSuccess
    }

    override suspend fun updateFlyerUrl(id: String, flyerUrl: String, flyerPosition: String?): Boolean {
        val body = buildMap<String, String> {
            put("flyer_url", flyerUrl)
            if (flyerPosition != null) put("flyer_position", flyerPosition)
        }
        val response = client.patch("$apiUrl/tournaments?id=eq.$id") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
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

    override suspend fun setCategoryColors(
        tournamentId: String,
        categoryColors: Map<Int, String>
    ): Boolean {
        if (categoryColors.isEmpty()) return true

        // Update each category color individually
        var allSuccess = true
        for ((categoryId, color) in categoryColors) {
            val url = "$apiUrl/tournament_categories?tournament_id=eq.$tournamentId&category_id=eq.$categoryId"
            val response = client.patch(url) {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(mapOf("color" to color))
            }

            if (!response.status.isSuccess()) {
                allSuccess = false
            }
        }

        return allSuccess
    }

    override suspend fun getSchedulingConfig(tournamentId: String): models.tournament.SchedulingConfigResponse? {
        val response = client.get("$apiUrl/tournament_scheduling_config") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val configs = json.decodeFromString<List<SchedulingConfigRaw>>(body)
            configs.firstOrNull()?.toResponse()
        } else {
            null
        }
    }

    override suspend fun saveSchedulingConfig(
        tournamentId: String,
        config: models.tournament.SchedulingConfigRequest
    ): Boolean {
        // Check if config exists
        val existing = getSchedulingConfig(tournamentId)

        val payload = SchedulingConfigRaw(
            tournamentId = tournamentId,
            courts = json.encodeToString(config.courts),
            matchDurationMinutes = config.matchDurationMinutes,
            tournamentDays = config.tournamentDays
        )

        val response = if (existing != null) {
            // Update existing
            client.patch("$apiUrl/tournament_scheduling_config?tournament_id=eq.$tournamentId") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        } else {
            // Insert new
            client.post("$apiUrl/tournament_scheduling_config") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }

        val success = response.status.isSuccess()
        if (!success) {
        }
        return success
    }

    override suspend fun getRestrictionConfig(tournamentId: String): models.tournament.RestrictionConfigResponse? {
        val response = client.get("$apiUrl/tournament_restriction_config") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "*")
        }

        return if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            val configs = json.decodeFromString<List<RestrictionConfigRaw>>(body)
            configs.firstOrNull()?.toResponse()
        } else {
            null
        }
    }

    override suspend fun saveRestrictionConfig(
        tournamentId: String,
        config: models.tournament.RestrictionConfigRequest
    ): Boolean {
        val existing = getRestrictionConfig(tournamentId)

        val payload = RestrictionConfigRaw(
            tournamentId = tournamentId,
            enabled = config.enabled,
            availableDays = config.availableDays,
            timeRangeFrom = config.timeRangeFrom,
            timeRangeTo = config.timeRangeTo,
            timeSlotMinutes = config.timeSlotMinutes
        )

        val response = if (existing != null) {
            client.patch("$apiUrl/tournament_restriction_config?tournament_id=eq.$tournamentId") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        } else {
            client.post("$apiUrl/tournament_restriction_config") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }

        return response.status.isSuccess()
    }
}

@Serializable
private data class RestrictionConfigRaw(
    @SerialName("tournament_id") val tournamentId: String,
    val enabled: Boolean = false,
    @SerialName("available_days") val availableDays: List<Int> = emptyList(),
    @SerialName("time_range_from") val timeRangeFrom: String? = null,
    @SerialName("time_range_to") val timeRangeTo: String? = null,
    @SerialName("time_slot_minutes") val timeSlotMinutes: Int = 60
) {
    fun toResponse(): models.tournament.RestrictionConfigResponse {
        return models.tournament.RestrictionConfigResponse(
            tournamentId = tournamentId,
            enabled = enabled,
            availableDays = availableDays,
            timeRangeFrom = timeRangeFrom,
            timeRangeTo = timeRangeTo,
            timeSlotMinutes = timeSlotMinutes
        )
    }
}

@Serializable
private data class SchedulingConfigRaw(
    @SerialName("tournament_id") val tournamentId: String,
    val courts: String, // JSON string
    @SerialName("match_duration_minutes") val matchDurationMinutes: Int,
    @SerialName("tournament_days") val tournamentDays: List<String>
) {
    fun toResponse(): models.tournament.SchedulingConfigResponse {
        val courtsList = try {
            kotlinx.serialization.json.Json.decodeFromString<List<models.tournament.CourtConfig>>(courts)
        } catch (e: Exception) {
            emptyList()
        }
        return models.tournament.SchedulingConfigResponse(
            tournamentId = tournamentId,
            courts = courtsList,
            matchDurationMinutes = matchDurationMinutes,
            tournamentDays = tournamentDays
        )
    }
}

@Serializable
data class SupabaseError(
    val code: String? = null,
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null,
)

@Serializable
data class OrganizerInfo(
    val name: String,
    @SerialName("logo_url") val logoUrl: String? = null
)

@Serializable
data class TournamentRawResponse(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val type: String,
    @SerialName("max_points") val maxPoints: String? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    @SerialName("flyer_position") val flyerPosition: String? = null,
    val categoryIds: List<String> = emptyList(),
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("registration_open") val registrationOpen: Boolean,
    @SerialName("club_logo_url") val clubLogoUrl: String? = null,
    @SerialName("organizer_id") val organizerId: String? = null,
    val organizers: OrganizerInfo? = null,
    @SerialName("city_id") val cityId: Int? = null,
    @SerialName("padel_club_id") val padelClubId: Int? = null,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean = false,
    @SerialName("payments_enabled") val paymentsEnabled: Boolean = true,
    @SerialName("show_registered_players") val showRegisteredPlayers: Boolean = true,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("featured_zone") val featuredZone: String? = null,
) {
    fun toTournamentResponse(): TournamentResponse {
        return TournamentResponse(
            id = id,
            name = name,
            startDate = startDate,
            endDate = endDate,
            location = location,
            latitude = latitude,
            longitude = longitude,
            type = type,
            maxPoints = maxPoints,
            flyerUrl = flyerUrl,
            flyerPosition = flyerPosition,
            categoryIds = categoryIds,
            isEnabled = isEnabled,
            registrationOpen = registrationOpen,
            clubLogoUrl = clubLogoUrl,
            organizerId = organizerId,
            organizerName = organizers?.name,
            organizerLogoUrl = organizers?.logoUrl,
            cityId = cityId,
            padelClubId = padelClubId,
            allowPlayerScores = allowPlayerScores,
            paymentsEnabled = paymentsEnabled,
            showRegisteredPlayers = showRegisteredPlayers,
            isFeatured = isFeatured,
            featuredZone = featuredZone,
        )
    }
}
