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
            // Query organization_members with joined profile data
            val response = client.get("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "id,organizer_id,user_uid,role,created_at,profiles(display_name,email)")
                parameter("organizer_id", "eq.$organizerId")
                parameter("order", "created_at.asc")
            }

            if (response.status.isSuccess()) {
                @Serializable
                data class ProfileData(
                    @SerialName("display_name")
                    val displayName: String? = null,
                    val email: String? = null
                )

                @Serializable
                data class MemberWithProfile(
                    val id: String,
                    @SerialName("organizer_id")
                    val organizerId: String,
                    @SerialName("user_uid")
                    val userUid: String,
                    val role: String,
                    @SerialName("created_at")
                    val createdAt: String,
                    val profiles: ProfileData? = null
                )

                val members = json.decodeFromString<List<MemberWithProfile>>(response.bodyAsText())
                members.map { member ->
                    OrganizationMemberResponse(
                        id = member.id,
                        organizerId = member.organizerId,
                        userUid = member.userUid,
                        role = member.role,
                        createdAt = member.createdAt,
                        userName = member.profiles?.displayName,
                        userEmail = member.profiles?.email
                    )
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("💥 [OrganizationTeamRepo] getMembers error: ${e.message}")
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
            println("💥 [OrganizationTeamRepo] getInvitations error: ${e.message}")
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
                println("❌ [OrganizationTeamRepo] createInvitation failed: ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
            println("💥 [OrganizationTeamRepo] createInvitation error: ${e.message}")
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
            println("💥 [OrganizationTeamRepo] deleteInvitation error: ${e.message}")
            false
        }
    }

    override suspend fun joinWithCode(code: String, userUid: String): JoinOrganizationResult {
        return try {
            @Serializable
            data class JoinPayload(
                @SerialName("invitation_code")
                val invitationCode: String,
                @SerialName("joining_user_uid")
                val joiningUserUid: String
            )

            val response = client.post("$apiUrl/rpc/join_organization_with_code") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(JoinPayload(code.uppercase(), userUid))
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                println("📦 [OrganizationTeamRepo] joinWithCode response: $body")
                json.decodeFromString<JoinOrganizationResult>(body)
            } else {
                println("❌ [OrganizationTeamRepo] joinWithCode failed: ${response.bodyAsText()}")
                JoinOrganizationResult(
                    success = false,
                    message = "Failed to join organization"
                )
            }
        } catch (e: Exception) {
            println("💥 [OrganizationTeamRepo] joinWithCode error: ${e.message}")
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
            println("💥 [OrganizationTeamRepo] getUserOrganizations error: ${e.message}")
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
            println("💥 [OrganizationTeamRepo] userHasAccess error: ${e.message}")
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
            println("💥 [OrganizationTeamRepo] removeMember error: ${e.message}")
            false
        }
    }

    override suspend fun getMemberById(memberId: String): OrganizationMemberResponse? {
        return try {
            val response = client.get("$apiUrl/organization_members") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*")
                parameter("id", "eq.$memberId")
            }

            if (response.status.isSuccess()) {
                json.decodeFromString<List<OrganizationMemberResponse>>(response.bodyAsText()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            println("💥 [OrganizationTeamRepo] getMemberById error: ${e.message}")
            null
        }
    }
}
