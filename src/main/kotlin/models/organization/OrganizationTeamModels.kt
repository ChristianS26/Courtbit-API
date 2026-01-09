package models.organization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// MARK: - Response Models

@Serializable
data class OrganizationMemberResponse(
    val id: String,
    @SerialName("organizer_id")
    val organizerId: String,
    @SerialName("user_uid")
    val userUid: String,
    val role: String,
    @SerialName("created_at")
    val createdAt: String,
    // Joined fields from profiles
    @SerialName("user_name")
    val userName: String? = null,
    @SerialName("user_email")
    val userEmail: String? = null
)

@Serializable
data class OrganizationInvitationResponse(
    val id: String,
    @SerialName("organizer_id")
    val organizerId: String,
    val code: String,
    @SerialName("created_by_uid")
    val createdByUid: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("used_at")
    val usedAt: String? = null,
    @SerialName("used_by_uid")
    val usedByUid: String? = null,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class JoinOrganizationResult(
    val success: Boolean,
    @SerialName("organizer_id")
    val organizerId: String? = null,
    @SerialName("organizer_name")
    val organizerName: String? = null,
    @SerialName("error_message")
    val message: String? = null
)

@Serializable
data class UserOrganization(
    @SerialName("organizer_id")
    val organizerId: String,
    @SerialName("organizer_name")
    val organizerName: String,
    val role: String,
    @SerialName("joined_at")
    val joinedAt: String
)

// MARK: - Request Models

@Serializable
data class CreateInvitationRequest(
    @SerialName("organizer_id")
    val organizerId: String
)

@Serializable
data class JoinOrganizationRequest(
    val code: String,
    val email: String,
    val name: String
)

@Serializable
data class RemoveMemberRequest(
    @SerialName("member_id")
    val memberId: String
)
