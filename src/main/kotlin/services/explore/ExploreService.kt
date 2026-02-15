package services.explore

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import models.explore.ExploreEvent
import models.explore.ExploreEventsResponse
import models.organizer.OrganizerResponse
import repositories.organizer.OrganizerRepository
import repositories.league.SeasonRepository
import repositories.tournament.TournamentRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ExploreService(
    private val tournamentRepository: TournamentRepository,
    private val seasonRepository: SeasonRepository,
    private val organizerRepository: OrganizerRepository
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun getExploreEvents(page: Int, pageSize: Int): ExploreEventsResponse = coroutineScope {
        val tournamentsDeferred = async { tournamentRepository.getAll() }
        val seasonsDeferred = async { seasonRepository.getAll() }
        val organizersDeferred = async { organizerRepository.getAll() }

        val tournaments = tournamentsDeferred.await()
        val seasons = seasonsDeferred.await()
        val organizers = organizersDeferred.await()

        val organizerMap = organizers.associateBy { it.id }
        val today = LocalDate.now()

        val tournamentEvents = tournaments
            .filter { it.isEnabled }
            .filter {
                try {
                    val endDate = LocalDate.parse(it.endDate, dateFormatter)
                    !endDate.isBefore(today)
                } catch (e: Exception) {
                    true
                }
            }
            .map { t ->
                val org = t.organizerId?.let { organizerMap[it] }
                ExploreEvent(
                    id = t.id,
                    name = t.name,
                    type = "tournament",
                    startDate = t.startDate,
                    endDate = t.endDate,
                    location = t.location,
                    flyerUrl = t.flyerUrl,
                    registrationOpen = t.registrationOpen,
                    isActive = true,
                    organizerId = t.organizerId,
                    organizerName = t.organizerName ?: org?.name,
                    organizerLogoUrl = t.organizerLogoUrl ?: org?.logoUrl,
                    organizerIsVerified = org?.isVerified ?: false
                )
            }

        val seasonEvents = seasons
            .filter { it.isActive }
            .map { s ->
                val org = s.organizerId?.let { organizerMap[it] }
                ExploreEvent(
                    id = s.id,
                    name = s.name,
                    type = "league",
                    startDate = s.startDate,
                    endDate = s.endDate,
                    location = null,
                    flyerUrl = null,
                    registrationOpen = s.registrationsOpen,
                    isActive = true,
                    organizerId = s.organizerId,
                    organizerName = s.organizerName ?: org?.name,
                    organizerLogoUrl = s.organizerLogoURL ?: org?.logoUrl,
                    organizerIsVerified = org?.isVerified ?: false
                )
            }

        val allEvents = (tournamentEvents + seasonEvents).sortedBy { event ->
            try {
                LocalDate.parse(event.startDate, dateFormatter)
            } catch (e: Exception) {
                LocalDate.MAX
            }
        }

        val offset = (page - 1) * pageSize
        val paginatedEvents = allEvents.drop(offset).take(pageSize)
        val hasMore = offset + pageSize < allEvents.size

        ExploreEventsResponse(
            events = paginatedEvents,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore
        )
    }
}
