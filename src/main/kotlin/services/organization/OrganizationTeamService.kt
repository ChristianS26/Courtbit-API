package services.organization

import models.organization.JoinOrganizationResult
import models.organization.OrganizationInvitationResponse
import models.organization.OrganizationMemberResponse
import models.organization.UserOrganization
import repositories.organization.OrganizationTeamRepository
import repositories.organizer.OrganizerRepository

class OrganizationTeamService(
    private val repository: OrganizationTeamRepository,
    private val organizerRepository: OrganizerRepository
) {

    /**
     * Get all members of an organization
     * Only accessible to organization owner or members
     */
    suspend fun getMembers(
        organizerId: String,
        requestingUserUid: String
    ): Result<List<OrganizationMemberResponse>> {
        // Verify user has access to this organizer
        if (!hasAccess(requestingUserUid, organizerId)) {
            return Result.failure(
                IllegalAccessException("You don't have access to this organization")
            )
        }

        return Result.success(repository.getMembers(organizerId))
    }

    /**
     * Get all active invitations for an organization
     * Only accessible to organization owner
     */
    suspend fun getInvitations(
        organizerId: String,
        requestingUserUid: String
    ): Result<List<OrganizationInvitationResponse>> {
        // Verify user is the owner
        if (!isOwner(requestingUserUid, organizerId)) {
            return Result.failure(
                IllegalAccessException("Only the organization owner can view invitations")
            )
        }

        return Result.success(repository.getInvitations(organizerId))
    }

    /**
     * Create a new invitation code
     * Only accessible to organization owner
     */
    suspend fun createInvitation(
        organizerId: String,
        requestingUserUid: String
    ): Result<OrganizationInvitationResponse> {
        // Verify user is the owner
        if (!isOwner(requestingUserUid, organizerId)) {
            return Result.failure(
                IllegalAccessException("Only the organization owner can create invitations")
            )
        }

        val invitation = repository.createInvitation(organizerId, requestingUserUid)
        return if (invitation != null) {
            Result.success(invitation)
        } else {
            Result.failure(IllegalStateException("Failed to create invitation"))
        }
    }

    /**
     * Delete/revoke an invitation
     * Only accessible to organization owner
     */
    suspend fun deleteInvitation(
        invitationId: String,
        organizerId: String,
        requestingUserUid: String
    ): Result<Boolean> {
        // Verify user is the owner
        if (!isOwner(requestingUserUid, organizerId)) {
            return Result.failure(
                IllegalAccessException("Only the organization owner can delete invitations")
            )
        }

        val deleted = repository.deleteInvitation(invitationId)
        return if (deleted) {
            Result.success(true)
        } else {
            Result.failure(IllegalStateException("Failed to delete invitation"))
        }
    }

    /**
     * Join an organization using an invitation code
     */
    suspend fun joinWithCode(
        code: String,
        userUid: String
    ): Result<JoinOrganizationResult> {
        // Validate code format (6 alphanumeric characters)
        val cleanCode = code.trim().uppercase()
        if (cleanCode.length != 6 || !cleanCode.all { it.isLetterOrDigit() }) {
            return Result.failure(
                IllegalArgumentException("Invalid invitation code format")
            )
        }

        val result = repository.joinWithCode(cleanCode, userUid)

        return if (result.success) {
            Result.success(result)
        } else {
            Result.failure(
                IllegalArgumentException(result.message ?: "Failed to join organization")
            )
        }
    }

    /**
     * Get all organizations a user belongs to
     */
    suspend fun getUserOrganizations(userUid: String): List<UserOrganization> {
        return repository.getUserOrganizations(userUid)
    }

    /**
     * Remove a member from the organization
     * Only accessible to organization owner
     * Cannot remove the owner
     */
    suspend fun removeMember(
        memberId: String,
        organizerId: String,
        requestingUserUid: String
    ): Result<Boolean> {
        // Verify user is the owner
        if (!isOwner(requestingUserUid, organizerId)) {
            return Result.failure(
                IllegalAccessException("Only the organization owner can remove members")
            )
        }

        // Get the member to verify they're not the owner
        val member = repository.getMemberById(memberId)
        if (member == null) {
            return Result.failure(IllegalArgumentException("Member not found"))
        }

        // Verify the member belongs to this organization
        if (member.organizerId != organizerId) {
            return Result.failure(IllegalArgumentException("Member not found in this organization"))
        }

        // Cannot remove owner
        if (member.role == "owner") {
            return Result.failure(
                IllegalStateException("Cannot remove the organization owner")
            )
        }

        val removed = repository.removeMember(memberId)
        return if (removed) {
            Result.success(true)
        } else {
            Result.failure(IllegalStateException("Failed to remove member"))
        }
    }

    /**
     * Check if user has access to an organizer (owner or member)
     */
    suspend fun hasAccess(userUid: String, organizerId: String): Boolean {
        return repository.userHasAccess(userUid, organizerId)
    }

    /**
     * Check if user is the owner of an organizer
     */
    private suspend fun isOwner(userUid: String, organizerId: String): Boolean {
        val organizer = organizerRepository.getById(organizerId)
        return organizer?.createdByUid == userUid
    }
}
