package services.league

import models.league.CreateSeasonRequest
import models.league.SeasonResponse
import org.slf4j.LoggerFactory
import repositories.league.LeagueCategoryRepository
import repositories.league.MatchDayRepository
import repositories.league.SeasonCourtRepository
import repositories.league.SeasonRepository

class SeasonService(
    private val repository: SeasonRepository,
    private val categoryRepository: LeagueCategoryRepository,
    private val matchDayRepository: MatchDayRepository,
    private val courtRepository: SeasonCourtRepository
) {
    private val logger = LoggerFactory.getLogger(SeasonService::class.java)

    suspend fun createSeason(
        request: CreateSeasonRequest,
        organizerId: String
    ): Result<SeasonResponse> {
        // Auto-inject organizer
        val seasonWithOrganizer = request.copy(organizerId = organizerId)

        val created = repository.create(seasonWithOrganizer)
        if (created == null) {
            return Result.failure(IllegalStateException("Failed to create season"))
        }

        // Create court records for the season
        val courtCount = request.numberOfCourts.coerceIn(1, 20)
        val courts = courtRepository.bulkCreate(created.id, courtCount)
        if (courts.isEmpty()) {
            logger.warn("Failed to create courts for season ${created.id}, but season was created")
        } else {
            logger.info("Created ${courts.size} courts for season ${created.id}")
        }

        return Result.success(created)
    }

    /**
     * Get all seasons with open registrations (for player discovery)
     */
    suspend fun getPublicSeasons(): List<SeasonResponse> {
        return repository.getAll().filter { it.registrationsOpen }
    }

    suspend fun deleteSeason(id: String, organizerId: String): Result<Boolean> {
        // Verify ownership
        val season = repository.getById(id)
            ?: return Result.failure(IllegalArgumentException("Season not found"))

        if (season.organizerId != organizerId) {
            return Result.failure(IllegalAccessException("Not authorized"))
        }

        // Check for dependencies
        val categories = categoryRepository.getBySeasonId(id)
        val hasCalendars = categories.any { category ->
            matchDayRepository.getByCategoryId(category.id).isNotEmpty()
        }

        if (hasCalendars) {
            return Result.failure(
                IllegalStateException("Cannot delete season with active calendars. Delete categories first.")
            )
        }

        val deleted = repository.delete(id)
        return if (deleted) Result.success(true)
        else Result.failure(IllegalStateException("Failed to delete"))
    }
}
