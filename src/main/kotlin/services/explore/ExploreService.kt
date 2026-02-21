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
        radiusKm: Double? = null,
        userId: String? = null
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
                    flyerPosition = t.flyerPosition,
                    registrationOpen = t.registrationOpen,
                    isActive = true,
                    organizerId = t.organizerId,
                    organizerName = t.organizerName ?: org?.name,
                    organizerLogoUrl = t.organizerLogoUrl ?: org?.logoUrl,
                    organizerIsVerified = org?.isVerified ?: false,
                    organizerPrimaryColor = org?.primaryColor,
                    isFeatured = t.isFeatured
                )
            }

        val seasonEvents = seasons
            .filter { it.isActive }
            .filter {
                try {
                    val endDate = it.endDate?.let { d -> LocalDate.parse(d, dateFormatter) }
                    endDate == null || !endDate.isBefore(today)
                } catch (e: Exception) {
                    true
                }
            }
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
                    organizerPrimaryColor = org?.primaryColor,
                    isFeatured = s.isFeatured
                )
            }

        var allEvents = tournamentEvents + seasonEvents

        // Filter by radius if location provided
        if (userLat != null && userLng != null && radiusKm != null) {
            allEvents = allEvents.filter { event ->
                val eLat = event.latitude ?: return@filter false
                val eLng = event.longitude ?: return@filter false
                GeoUtils.isWithinRadius(userLat, userLng, eLat, eLng, radiusKm)
            }
        }

        // Sort by distance if location provided, otherwise by start date
        allEvents = if (userLat != null && userLng != null) {
            allEvents.sortedBy { event ->
                val eLat = event.latitude
                val eLng = event.longitude
                if (eLat != null && eLng != null) {
                    GeoUtils.haversineDistance(userLat, userLng, eLat, eLng)
                } else {
                    Double.MAX_VALUE
                }
            }
        } else {
            allEvents.sortedBy { event ->
                try {
                    LocalDate.parse(event.startDate, dateFormatter)
                } catch (e: Exception) {
                    LocalDate.MAX
                }
            }
        }

        // Separate featured events (max 2, only on page 1)
        val featuredEvents = if (page == 1) {
            allEvents.filter { it.isFeatured }.take(2)
        } else {
            emptyList()
        }

        // Regular events exclude featured
        val featuredIds = featuredEvents.map { "${it.type}_${it.id}" }.toSet()
        val regularEvents = allEvents.filter { "${it.type}_${it.id}" !in featuredIds }

        val offset = (page - 1) * pageSize
        val paginatedEvents = regularEvents.drop(offset).take(pageSize)
        val hasMore = offset + pageSize < regularEvents.size

        // Enrich with follow status if authenticated
        val enrichedEvents: List<ExploreEvent>
        val enrichedFeatured: List<ExploreEvent>
        if (userId != null) {
            val allOrgIds = (paginatedEvents + featuredEvents)
                .mapNotNull { it.organizerId }
                .toSet()
            val followedIds = followService.getFollowedOrganizerIds(userId, allOrgIds)
            enrichedEvents = paginatedEvents.map { event ->
                event.copy(isFollowingOrganizer = event.organizerId != null && event.organizerId in followedIds)
            }
            enrichedFeatured = featuredEvents.map { event ->
                event.copy(isFollowingOrganizer = event.organizerId != null && event.organizerId in followedIds)
            }
        } else {
            enrichedEvents = paginatedEvents
            enrichedFeatured = featuredEvents
        }

        ExploreEventsResponse(
            events = enrichedEvents,
            featured = enrichedFeatured,
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
        userId: String? = null,
    ): ExploreOrganizersResponse {
        var allOrganizers = getExploreOrganizers()

        // Featured: top 8 verified orgs by mixed score, only on page 1 and without query
        val featured = if (page == 1 && query.isNullOrBlank()) {
            allOrganizers
                .filter { it.isVerified }
                .filter { it.eventCount > 0 || it.followerCount > 0 }
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

        // Enrich with follow status if authenticated
        val enrichedOrganizers: List<ExploreOrganizer>
        val enrichedFeatured: List<ExploreOrganizer>
        if (userId != null) {
            val allOrgIds = (paginatedOrganizers + featured).map { it.id }.toSet()
            val followedIds = followService.getFollowedOrganizerIds(userId, allOrgIds)
            enrichedOrganizers = paginatedOrganizers.map { it.copy(isFollowingOrganizer = it.id in followedIds) }
            enrichedFeatured = featured.map { it.copy(isFollowingOrganizer = it.id in followedIds) }
        } else {
            enrichedOrganizers = paginatedOrganizers
            enrichedFeatured = featured
        }

        return ExploreOrganizersResponse(
            organizers = enrichedOrganizers,
            featured = enrichedFeatured,
            page = page,
            pageSize = pageSize,
            hasMore = hasMore,
        )
    }
}
