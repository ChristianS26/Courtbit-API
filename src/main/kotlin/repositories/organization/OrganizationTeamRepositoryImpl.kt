package repositories.organization

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.organization.JoinOrganizationResult
import models.organization.OrganizationInvitationResponse
import models.organization.OrganizationMemberResponse
import models.organization.UserOrganization

class OrganizationTeamRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : OrganizationTeamRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun getMembers(organizerId: String): List<OrganizationMemberResponse> {
        return try {
            val response = client.get("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "id,organizer_id,user_uid,role,joined_at,user_name,user_email")
                parameter("organizer_id", "eq.$organizerId")
                parameter("order", "joined_at.asc")
            }

            if (response.status.isSuccess()) {
                @Serializable
                data class MemberRow(
                    val id: String,
                    @SerialName("organizer_id")
                    val organizerId: String,
                    @SerialName("user_uid")
                    val userUid: String,
                    val role: String,
                    @SerialName("joined_at")
                    val joinedAt: String,
                    @SerialName("user_name")
                    val userName: String? = null,
                    @SerialName("user_email")
                    val userEmail: String? = null
                )

                val members = json.decodeFromString<List<MemberRow>>(response.bodyAsText())
                members.map { member ->
                    OrganizationMemberResponse(
                        id = member.id,
                        organizerId = member.organizerId,
                        userUid = member.userUid,
                        role = member.role,
                        createdAt = member.joinedAt,
                        userName = member.userName,
                        userEmail = member.userEmail
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getInvitations(organizerId: String): List<OrganizationInvitationResponse> {
        return try {
            val response = client.get("$apiUrl/organization_invitations") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("organizer_id", "eq.$organizerId")
                parameter("used_at", "is.null") // Only active (unused) invitations
                parameter("expires_at", "gt.now()") // Not expired
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString(
                    ListSerializer(OrganizationInvitationResponse.serializer()),
                    response.bodyAsText()
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun createInvitation(
        organizerId: String,
        createdByUid: String
    ): OrganizationInvitationResponse? {
        return try {
            @Serializable
            data class CreatePayload(
                @SerialName("organizer_id")
                val organizerId: String,
                @SerialName("created_by_uid")
                val createdByUid: String
            )

            val response = client.post("$apiUrl/organization_invitations") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(listOf(CreatePayload(organizerId, createdByUid)))
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<OrganizationInvitationResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteInvitation(invitationId: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/organization_invitations") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("id", "eq.$invitationId")
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun joinWithCode(code: String, userUid: String, userEmail: String, userName: String): JoinOrganizationResult {
        return try {
            @Serializable
            data class JoinPayload(
                @SerialName("p_code")
                val code: String,
                @SerialName("p_user_uid")
                val userUid: String,
                @SerialName("p_user_email")
                val userEmail: String,
                @SerialName("p_user_name")
                val userName: String
            )

            val response = client.post("$apiUrl/rpc/join_organization_with_code") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(JoinPayload(code.uppercase(), userUid, userEmail, userName))
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                // Database function returns a table (array), get first element
                val results = json.decodeFromString<List<JoinOrganizationResult>>(body)
                results.firstOrNull() ?: JoinOrganizationResult(
                    success = false,
                    message = "No response from database"
                )
            } else {
                JoinOrganizationResult(
                    success = false,
                    message = "Failed to join organization"
                )
            }
        } catch (e: Exception) {
            JoinOrganizationResult(
                success = false,
                message = e.message ?: "Unknown error"
            )
        }
    }

    override suspend fun getUserOrganizations(userUid: String): List<UserOrganization> {
        return try {
            @Serializable
            data class GetOrgsPayload(
                @SerialName("p_user_uid")
                val userUid: String
            )

            val response = client.post("$apiUrl/rpc/get_user_organizations") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(GetOrgsPayload(userUid))
            }

            if (response.status.isSuccess()) {
                json.decodeFromString(
                    ListSerializer(UserOrganization.serializer()),
                    response.bodyAsText()
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun userHasAccess(userUid: String, organizerId: String): Boolean {
        return try {
            @Serializable
            data class AccessPayload(
                @SerialName("p_user_uid")
                val userUid: String,
                @SerialName("p_organizer_id")
                val organizerId: String
            )

            val response = client.post("$apiUrl/rpc/user_has_organizer_access") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(AccessPayload(userUid, organizerId))
            }

            if (response.status.isSuccess()) {
                response.bodyAsText().toBoolean()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun removeMember(memberId: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("id", "eq.$memberId")
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getMemberById(memberId: String): OrganizationMemberResponse? {
        return try {
            val response = client.get("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "id,organizer_id,user_uid,role,joined_at,user_name,user_email")
                parameter("id", "eq.$memberId")
            }

            if (response.status.isSuccess()) {
                @Serializable
                data class MemberRow(
                    val id: String,
                    @SerialName("organizer_id")
                    val organizerId: String,
                    @SerialName("user_uid")
                    val userUid: String,
                    val role: String,
                    @SerialName("joined_at")
                    val joinedAt: String,
                    @SerialName("user_name")
                    val userName: String? = null,
                    @SerialName("user_email")
                    val userEmail: String? = null
                )

                val members = json.decodeFromString<List<MemberRow>>(response.bodyAsText())
                members.firstOrNull()?.let { member ->
                    OrganizationMemberResponse(
                        id = member.id,
                        organizerId = member.organizerId,
                        userUid = member.userUid,
                        role = member.role,
                        createdAt = member.joinedAt,
                        userName = member.userName,
                        userEmail = member.userEmail
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
