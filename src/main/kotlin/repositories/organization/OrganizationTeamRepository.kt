package repositories.organization

import models.organization.JoinOrganizationResult
import models.organization.OrganizationInvitationResponse
import models.organization.OrganizationMemberResponse
import models.organization.UserOrganization

interface OrganizationTeamRepository {
    /**
     * Get all members of an organization
     */
    suspend fun getMembers(organizerId: String): List<OrganizationMemberResponse>

    /**
     * Get all active invitations for an organization
     */
    suspend fun getInvitations(organizerId: String): List<OrganizationInvitationResponse>

    /**
     * Create a new invitation code
     */
    suspend fun createInvitation(organizerId: String, createdByUid: String): OrganizationInvitationResponse?

    /**
     * Delete/revoke an invitation
     */
    suspend fun deleteInvitation(invitationId: String): Boolean

    /**
     * Join an organization using an invitation code (calls database function)
     */
    suspend fun joinWithCode(code: String, userUid: String): JoinOrganizationResult

    /**
     * Get all organizations a user belongs to (calls database function)
     */
    suspend fun getUserOrganizations(userUid: String): List<UserOrganization>

    /**
     * Check if user has access to an organizer (calls database function)
     */
    suspend fun userHasAccess(userUid: String, organizerId: String): Boolean

    /**
     * Remove a member from the organization
     */
    suspend fun removeMember(memberId: String): Boolean

    /**
     * Get member by ID
     */
    suspend fun getMemberById(memberId: String): OrganizationMemberResponse?
}
