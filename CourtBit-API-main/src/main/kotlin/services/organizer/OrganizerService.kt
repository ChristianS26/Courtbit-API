package services.organizer

import models.organizer.CreateOrganizerRequest
import models.organizer.OrganizerResponse
import models.organizer.OrganizerStatisticsResponse
import models.organizer.UpdateOrganizerRequest
import repositories.organizer.OrganizerRepository

class OrganizerService(
    private val repository: OrganizerRepository
) {

    /**
     * Get all organizers
     */
    suspend fun getAllOrganizers(): List<OrganizerResponse> {
        return repository.getAll()
    }

    /**
     * Get organizer by ID
     */
    suspend fun getOrganizerById(id: String): OrganizerResponse? {
        return repository.getById(id)
    }

    /**
     * Get organizer for the authenticated user
     */
    suspend fun getMyOrganizer(userUid: String): OrganizerResponse? {
        return repository.getByUserUid(userUid)
    }

    /**
     * Check if user is an organizer
     */
    suspend fun isUserOrganizer(userUid: String): Boolean {
        return repository.isUserOrganizer(userUid)
    }

    /**
     * Create a new organizer
     * Business rule: User can only have one organizer
     */
    suspend fun createOrganizer(
        request: CreateOrganizerRequest,
        userUid: String
    ): Result<OrganizerResponse> {
        // Check if user already has an organizer
        val existing = repository.getByUserUid(userUid)
        if (existing != null) {
            return Result.failure(
                IllegalStateException("User already has an organizer profile")
            )
        }

        // Validate email format
        if (!request.contactEmail.contains("@")) {
            return Result.failure(
                IllegalArgumentException("Invalid email format")
            )
        }

        // Validate hex color format
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        if (!hexPattern.matches(request.primaryColor)) {
            return Result.failure(
                IllegalArgumentException("Invalid primary color format. Must be #RRGGBB")
            )
        }
        if (!hexPattern.matches(request.secondaryColor)) {
            return Result.failure(
                IllegalArgumentException("Invalid secondary color format. Must be #RRGGBB")
            )
        }

        // Create organizer
        val created = repository.create(request, userUid)
        return if (created != null) {
            Result.success(created)
        } else {
            Result.failure(IllegalStateException("Failed to create organizer"))
        }
    }

    /**
     * Update an existing organizer
     * Business rule: Only the creator can update their organizer
     */
    suspend fun updateOrganizer(
        id: String,
        request: UpdateOrganizerRequest,
        userUid: String
    ): Result<Boolean> {
        // Verify ownership
        val organizer = repository.getById(id)
        if (organizer == null) {
            return Result.failure(IllegalArgumentException("Organizer not found"))
        }

        if (organizer.createdByUid != userUid) {
            return Result.failure(
                IllegalAccessException("Only the creator can update this organizer")
            )
        }

        // Validate email format if provided
        if (request.contactEmail != null && !request.contactEmail.contains("@")) {
            return Result.failure(
                IllegalArgumentException("Invalid email format")
            )
        }

        // Validate hex color formats if provided
        val hexPattern = Regex("^#[0-9A-Fa-f]{6}$")
        if (request.primaryColor != null && !hexPattern.matches(request.primaryColor)) {
            return Result.failure(
                IllegalArgumentException("Invalid primary color format. Must be #RRGGBB")
            )
        }
        if (request.secondaryColor != null && !hexPattern.matches(request.secondaryColor)) {
            return Result.failure(
                IllegalArgumentException("Invalid secondary color format. Must be #RRGGBB")
            )
        }

        // Update organizer
        val updated = repository.update(id, request)
        return if (updated) {
            Result.success(true)
        } else {
            Result.failure(IllegalStateException("Failed to update organizer"))
        }
    }

    /**
     * Delete an organizer
     * Business rule: Only the creator can delete their organizer
     */
    suspend fun deleteOrganizer(id: String, userUid: String): Result<Boolean> {
        // Verify ownership
        val organizer = repository.getById(id)
        if (organizer == null) {
            return Result.failure(IllegalArgumentException("Organizer not found"))
        }

        if (organizer.createdByUid != userUid) {
            return Result.failure(
                IllegalAccessException("Only the creator can delete this organizer")
            )
        }

        // Delete organizer
        val deleted = repository.delete(id)
        return if (deleted) {
            Result.success(true)
        } else {
            Result.failure(IllegalStateException("Failed to delete organizer"))
        }
    }

    /**
     * Get organizer statistics
     */
    suspend fun getOrganizerStatistics(organizerId: String): OrganizerStatisticsResponse? {
        return repository.getStatistics(organizerId)
    }
}
