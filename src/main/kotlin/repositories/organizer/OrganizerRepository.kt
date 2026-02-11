package repositories.organizer

import models.organizer.CreateOrganizerRequest
import models.organizer.OrganizerResponse
import models.organizer.OrganizerStatisticsResponse
import models.organizer.UpdateOrganizerRequest

interface OrganizerRepository {
    /**
     * Get all organizers
     */
    suspend fun getAll(): List<OrganizerResponse>

    /**
     * Get organizer by ID
     */
    suspend fun getById(id: String): OrganizerResponse?

    /**
     * Get organizer by user UID (using database function)
     */
    suspend fun getByUserUid(userUid: String): OrganizerResponse?

    /**
     * Check if user is an organizer (using database function)
     */
    suspend fun isUserOrganizer(userUid: String): Boolean

    /**
     * Create a new organizer
     */
    suspend fun create(request: CreateOrganizerRequest, createdByUid: String): OrganizerResponse?

    /**
     * Update an existing organizer
     */
    suspend fun update(id: String, request: UpdateOrganizerRequest): Boolean

    /**
     * Delete an organizer
     */
    suspend fun delete(id: String): Boolean

    /**
     * Get organizer statistics (using database function)
     */
    suspend fun getStatistics(organizerId: String): OrganizerStatisticsResponse?

    /**
     * Get the Stripe Connect account ID for an organizer
     */
    suspend fun getStripeAccountId(organizerId: String): String?

    /**
     * Update the Stripe Connect account ID for an organizer
     */
    suspend fun updateStripeAccountId(organizerId: String, stripeAccountId: String): Boolean
}
