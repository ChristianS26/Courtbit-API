package services.explore

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import models.explore.ExploreEvent
import models.explore.ExploreEventsResponse
import models.explore.ExploreOrganizer
import models.explore.ExploreOrganizersResponse
import models.organizer.OrganizerResponse
import repositories.organizer.OrganizerRepository
import repositories.league.SeasonRepository
import repositories.tournament.TournamentRepository
import services.follow.FollowService
import utils.GeoUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ExploreService(
    private val tournamentRepository: TournamentRepository,
    private val seasonRepository: SeasonRepository,
    private val organizerRepository: OrganizerRepository,
    private val followService: FollowService
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    suspend fun getExploreEvents(
        page: Int,
        pageSize: Int,
        userLat: Double? = null,
        userLng: Double? = null,
        radiusKm: Double? = null
    ): ExploreEventsResponse = coroutineScope {
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
                    latitude = t.latitude,
                    longitude = t.longitude,
                    flyerUrl = t.flyerUrl,
                    registrationOpen = t.registrationOpen,
                    isActive = true,
                    organizerId = t.organizerId,
                    organizerName = t.organizerName ?: org?.name,
                    organizerLogoUrl = t.organizerLogoUrl ?: org?.logoUrl,
                    organizerIsVerified = org?.isVerified ?: false,
                    organizerPrimaryColor = org?.primaryColor
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
                    location = s.location,
                    latitude = s.latitude,
                    longitude = s.longitude,
                    flyerUrl = null,
                    registrationOpen = s.registrationsOpen,
                    isActive = true,
                    organizerId = s.organizerId,
                    organizerName = s.organizerName ?: org?.name,
                    organizerLogoUrl = s.organizerLogoURL ?: org?.logoUrl,
                    organizerIsVerified = org?.isVerified ?: false,
                    organizerPrimaryColor = org?.primaryColor
                )
            }

        var allEvents = tournamentEvents + seasonEvents

        if (userLat != null && userLng != null) {
            if (radiusKm != null) {
                // Filter events within the radius
                allEvents = allEvents.filter { event ->
                    val eLat = event.latitude ?: return@filter false
                    val eLng = event.longitude ?: return@filter false
                    GeoUtils.isWithinRadius(userLat, userLng, eLat, eLng, radiusKm)
                }
            }
            // Sort by distance (closest first), events without coords go to the end
            allEvents = allEvents.sortedBy { event ->
                val eLat = event.latitude
                val eLng = event.longitude
                if (eLat != null && eLng != null) {
                    GeoUtils.haversineDistance(userLat, userLng, eLat, eLng)
                } else {
                    Double.MAX_VALUE
                }
            }
        } else {
            // Default: sort by start date
            allEvents = allEvents.sortedBy { event ->
                try {
                    LocalDate.parse(event.startDate, dateFormatter)
                } catch (e: Exception) {
                    LocalDate.MAX
                }
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

    suspend fun getExploreOrganizers(): List<ExploreOrganizer> = coroutineScope {
        val organizersDeferred = async { organizerRepository.getAll() }
        val tournamentsDeferred = async { tournamentRepository.getAll() }
        val seasonsDeferred = async { seasonRepository.getAll() }

        val organizers = organizersDeferred.await()
        val tournaments = tournamentsDeferred.await()
        val seasons = seasonsDeferred.await()

        // Count active events per organizer
        val eventCounts = mutableMapOf<String, Int>()
        tournaments.filter { it.isEnabled }.forEach { t ->
            t.organizerId?.let { eventCounts[it] = (eventCounts[it] ?: 0) + 1 }
        }
        seasons.filter { it.isActive }.forEach { s ->
            s.organizerId?.let { eventCounts[it] = (eventCounts[it] ?: 0) + 1 }
        }

        organizers.map { org ->
            val followerCount = try { followService.getFollowerCount(org.id) } catch (_: Exception) { 0L }
            ExploreOrganizer(
                id = org.id,
                name = org.name,
                logoUrl = org.logoUrl,
                primaryColor = org.primaryColor,
                isVerified = org.isVerified,
                location = org.location,
                latitude = org.latitude,
                longitude = org.longitude,
                followerCount = followerCount,
                eventCount = eventCounts[org.id] ?: 0
            )
        }.sortedByDescending { it.followerCount }
    }

    suspend fun searchOrganizers(
        query: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        userLat: Double? = null,
        userLng: Double? = null,
        sortBy: String = "followers",
        verifiedOnly: Boolean = false,
    ): ExploreOrganizersResponse {
        var allOrganizers = getExploreOrganizers()

        // Featured: top 8 by mixed score, only on page 1 and without query
        val featured = if (page == 1 && query.isNullOrBlank()) {
            allOrganizers
                .sortedByDescending { (it.eventCount * 2.0) + (it.followerCount * 1.0) }
                .take(8)
        } else {
            emptyList()
        }

        // Filter by name if query provided
        if (!query.isNullOrBlank()) {
            allOrganizers = allOrganizers.filter { it.name.contains(query, ignoreCase = true) }
        }

        // Filter verified only
        if (verifiedOnly) {
            allOrganizers = allOrganizers.filter { it.isVerified }
        }

        // Sort
        allOrganizers = when (sortBy) {
            "events" -> allOrganizers.sortedByDescending { it.eventCount }
            "distance" -> {
                if (userLat != null && userLng != null) {
                    allOrganizers.sortedBy { org ->
                        val oLat = org.latitude
                        val oLng = org.longitude
                        if (oLat != null && oLng != null) {
                            GeoUtils.haversineDistance(userLat, userLng, oLat, oLng)
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                } else {
                    allOrganizers.sortedByDescending { it.followerCount }
                }
            }
            else -> allOrganizers.sortedByDescending { it.followerCount }
        }

        val offset = (page - 1) * pageSize
        val paginatedOrganizers = allOrganizers.drop(offset).take(pageSize)
        val hasMore = offset + pageSize < allOrganizers.size

        return ExploreOrganizersResponse(
            organizers = paginatedOrganizers,
            featured = featured,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
        )
    }
}
