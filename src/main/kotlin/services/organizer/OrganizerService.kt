package services.organizer

import models.explore.ExploreEvent
import models.organizer.CreateOrganizerRequest
import models.organizer.OrganizerPublicProfileResponse
import models.organizer.OrganizerResponse
import models.organizer.OrganizerStatisticsResponse
import models.organizer.UpdateOrganizerRequest
import repositories.league.SeasonRepository
import repositories.organization.OrganizationTeamRepository
import repositories.organizer.OrganizerRepository
import repositories.tournament.TournamentRepository
import services.follow.FollowService
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class OrganizerService(
    private val repository: OrganizerRepository,
    private val organizationTeamRepository: OrganizationTeamRepository? = null,
    private val followService: FollowService? = null,
    private val tournamentRepository: TournamentRepository? = null,
    private val seasonRepository: SeasonRepository? = null
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

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
     * Business rule: Creator or team members can update the organizer
     */
    suspend fun updateOrganizer(
        id: String,
        request: UpdateOrganizerRequest,
        userUid: String
    ): Result<Boolean> {
        // Verify organizer exists
        val organizer = repository.getById(id)
        if (organizer == null) {
            return Result.failure(IllegalArgumentException("Organizer not found"))
        }

        // Check if user is the creator OR a team member
        val isCreator = organizer.createdByUid == userUid
        val isTeamMember = organizationTeamRepository?.userHasAccess(userUid, id) ?: false

        if (!isCreator && !isTeamMember) {
            return Result.failure(
                IllegalAccessException("Only the creator or team members can update this organizer")
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

    /**
     * Get public profile for an organizer.
     * Includes follower count, follow status, and upcoming/past events.
     */
    suspend fun getPublicProfile(organizerId: String, currentUserId: String?): OrganizerPublicProfileResponse? {
        val organizer = repository.getById(organizerId) ?: return null

        val followerCount = followService?.getFollowerCount(organizerId) ?: 0L
        val isFollowing = if (currentUserId != null) {
            followService?.isFollowing(currentUserId, organizerId) ?: false
        } else {
            false
        }

        val today = LocalDate.now()

        val tournaments = tournamentRepository?.getByOrganizerId(organizerId) ?: emptyList()
        val seasons = seasonRepository?.getByOrganizerId(organizerId) ?: emptyList()

        val tournamentEvents = tournaments
            .filter { it.isEnabled }
            .map { t ->
                ExploreEvent(
                    id = t.id,
                    name = t.name,
                    type = "tournament",
                    startDate = t.startDate,
                    endDate = t.endDate,
                    location = t.location,
                    latitude = t.latitude,
                    longitude = t.longitude,
                    flyerUrl = t.flyerUrl,
                    registrationOpen = t.registrationOpen,
                    isActive = true,
                    organizerId = t.organizerId,
                    organizerName = t.organizerName ?: organizer.name,
                    organizerLogoUrl = t.organizerLogoUrl ?: organizer.logoUrl,
                    organizerIsVerified = organizer.isVerified
                )
            }

        val seasonEvents = seasons.map { s ->
            ExploreEvent(
                id = s.id,
                name = s.name,
                type = "league",
                startDate = s.startDate,
                endDate = s.endDate,
                location = s.location,
                latitude = s.latitude,
                longitude = s.longitude,
                flyerUrl = null,
                registrationOpen = s.registrationsOpen,
                isActive = s.isActive,
                organizerId = s.organizerId,
                organizerName = s.organizerName ?: organizer.name,
                organizerLogoUrl = s.organizerLogoURL ?: organizer.logoUrl,
                organizerIsVerified = organizer.isVerified
            )
        }

        val allEvents = tournamentEvents + seasonEvents

        val upcomingEvents = allEvents.filter { event ->
            try {
                val endDate = if (event.endDate != null) {
                    LocalDate.parse(event.endDate, dateFormatter)
                } else {
                    LocalDate.parse(event.startDate, dateFormatter)
                }
                !endDate.isBefore(today)
            } catch (e: Exception) {
                true
            }
        }.sortedBy {
            try { LocalDate.parse(it.startDate, dateFormatter) } catch (e: Exception) { LocalDate.MAX }
        }

        val pastEvents = allEvents.filter { event ->
            try {
                val endDate = if (event.endDate != null) {
                    LocalDate.parse(event.endDate, dateFormatter)
                } else {
                    LocalDate.parse(event.startDate, dateFormatter)
                }
                endDate.isBefore(today)
            } catch (e: Exception) {
                false
            }
        }.sortedByDescending {
            try { LocalDate.parse(it.startDate, dateFormatter) } catch (e: Exception) { LocalDate.MIN }
        }

        return OrganizerPublicProfileResponse(
            id = organizer.id,
            name = organizer.name,
            description = organizer.description,
            logoUrl = organizer.logoUrl,
            primaryColor = organizer.primaryColor,
            contactEmail = organizer.contactEmail,
            contactPhone = organizer.contactPhone,
            instagram = organizer.instagram,
            facebook = organizer.facebook,
            isVerified = organizer.isVerified,
            location = organizer.location,
            latitude = organizer.latitude,
            longitude = organizer.longitude,
            followerCount = followerCount,
            isFollowing = isFollowing,
            upcomingEvents = upcomingEvents,
            pastEvents = pastEvents
        )
    }
}
