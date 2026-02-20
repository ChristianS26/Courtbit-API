package repositories.organizer

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.organizer.CreateOrganizerRequest
import models.organizer.OrganizerResponse
import models.organizer.OrganizerStatisticsResponse
import models.organizer.UpdateOrganizerRequest
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class OrganizerRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : OrganizerRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getAll(): List<OrganizerResponse> {
        return try {
            val response = client.get("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString(
                    ListSerializer(OrganizerResponse.serializer()),
                    response.bodyAsText()
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getById(id: String): OrganizerResponse? {
        return try {
            val response = client.get("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("id", "eq.$id")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<OrganizerResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getByUserUid(userUid: String): OrganizerResponse? {
        return try {
            // Call get_user_organizer database function
            @Serializable
            data class GetUserOrganizerPayload(val user_uid: String)


            val response = client.post("$apiUrl/rpc/get_user_organizer") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(GetUserOrganizerPayload(user_uid = userUid))
            }

            val body = response.bodyAsText()

            if (response.status.isSuccess()) {
                val result = json.decodeFromString<List<OrganizerResponse>>(body).firstOrNull()
                result
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun isUserOrganizer(userUid: String): Boolean {
        return try {
            // Call is_user_organizer database function
            @Serializable
            data class IsUserOrganizerPayload(val user_uid: String)

            val response = client.post("$apiUrl/rpc/is_user_organizer") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(IsUserOrganizerPayload(user_uid = userUid))
            }

            if (response.status.isSuccess()) {
                // RPC returns a boolean value
                response.bodyAsText().toBoolean()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun create(
        request: CreateOrganizerRequest,
        createdByUid: String
    ): OrganizerResponse? {
        return try {
            @Serializable
            data class CreatePayload(
                val name: String,
                val description: String,
                val contact_email: String,
                val contact_phone: String,
                val primary_color: String,
                val instagram: String?,
                val facebook: String?,
                val created_by_uid: String,
                val location: String?,
                val latitude: Double?,
                val longitude: Double?
            )

            val payload = CreatePayload(
                name = request.name,
                description = request.description,
                contact_email = request.contactEmail,
                contact_phone = request.contactPhone,
                primary_color = request.primaryColor,
                instagram = request.instagram,
                facebook = request.facebook,
                created_by_uid = createdByUid,
                location = request.location,
                latitude = request.latitude,
                longitude = request.longitude
            )

            val response = client.post("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(payload)) // Supabase expects array
            }

            if (response.status.isSuccess()) {
                val organizer = json.decodeFromString<List<OrganizerResponse>>(response.bodyAsText()).firstOrNull()

                // Add the creator as owner in organization_members
                if (organizer != null) {
                    addCreatorAsOwner(organizer.id, createdByUid, request.contactEmail, request.name)
                }

                organizer
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Add the creator as an owner in organization_members table
     */
    private suspend fun addCreatorAsOwner(
        organizerId: String,
        userUid: String,
        userEmail: String,
        organizerName: String
    ) {
        try {
            // Get user name from users table
            val userResponse = client.get("$apiUrl/users") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("uid", "eq.$userUid")
                parameter("select", "first_name,last_name")
            }

            var userName = organizerName // fallback to organizer name
            if (userResponse.status.isSuccess()) {
                @Serializable
                data class UserName(val first_name: String, val last_name: String)
                val users = json.decodeFromString<List<UserName>>(userResponse.bodyAsText())
                users.firstOrNull()?.let {
                    userName = "${it.first_name} ${it.last_name}".trim()
                }
            }

            @Serializable
            data class MemberPayload(
                val organizer_id: String,
                val user_uid: String,
                val user_email: String,
                val user_name: String,
                val role: String
            )

            val memberPayload = MemberPayload(
                organizer_id = organizerId,
                user_uid = userUid,
                user_email = userEmail,
                user_name = userName,
                role = "owner"
            )

            val memberResponse = client.post("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(listOf(memberPayload))
            }

            if (memberResponse.status.isSuccess()) {
                logger.info { "Added creator as owner for organizer $organizerId" }
            } else {
                logger.error { "Failed to add creator as owner for organizer $organizerId: ${memberResponse.status} - ${memberResponse.bodyAsText()}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Exception adding creator as owner for organizer $organizerId" }
        }
    }

    override suspend fun update(id: String, request: UpdateOrganizerRequest): Boolean {
        return try {
            @Serializable
            data class UpdatePayload(
                val name: String? = null,
                val description: String? = null,
                val contact_email: String? = null,
                val contact_phone: String? = null,
                val primary_color: String? = null,
                val logo_url: String? = null,
                val instagram: String? = null,
                val facebook: String? = null,
                val location: String? = null,
                val latitude: Double? = null,
                val longitude: Double? = null
            )

            val payload = UpdatePayload(
                name = request.name,
                description = request.description,
                contact_email = request.contactEmail,
                contact_phone = request.contactPhone,
                primary_color = request.primaryColor,
                logo_url = request.logoUrl,
                instagram = request.instagram,
                facebook = request.facebook,
                location = request.location,
                latitude = request.latitude,
                longitude = request.longitude
            )

            val response = client.patch("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                parameter("id", "eq.$id")
                setBody(payload)
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun delete(id: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("id", "eq.$id")
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getStatistics(organizerId: String): OrganizerStatisticsResponse? {
        return try {
            // Call get_organizer_statistics database function
            @Serializable
            data class GetStatisticsPayload(val organizer_uuid: String)

            val response = client.post("$apiUrl/rpc/get_organizer_statistics") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(GetStatisticsPayload(organizer_uuid = organizerId))
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<OrganizerStatisticsResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getStripeAccountId(organizerId: String): String? {
        return try {
            @Serializable
            data class StripeAccountRow(val stripe_account_id: String? = null)

            val response = client.get("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "stripe_account_id")
                parameter("id", "eq.$organizerId")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<StripeAccountRow>>(response.bodyAsText())
                    .firstOrNull()?.stripe_account_id
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun updateStripeAccountId(organizerId: String, stripeAccountId: String): Boolean {
        return try {
            @Serializable
            data class UpdateStripePayload(val stripe_account_id: String)

            val response = client.patch("$apiUrl/organizers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                parameter("id", "eq.$organizerId")
                setBody(UpdateStripePayload(stripe_account_id = stripeAccountId))
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }
}
