package services.league

import com.incodap.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import models.league.*
import repositories.league.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Service for auto-scheduling groups to time slots using CSP with MRV heuristic.
 *
 * The algorithm:
 * 1. Get all unassigned day groups for the matchday across all categories
 * 2. Get available slots (time slots × courts)
 * 3. For each group, calculate availability score for each slot
 * 4. Sort groups by MRV (Minimum Remaining Values) - groups with fewer good slots first
 * 5. Assign groups to their best available slot
 * 6. Return assignments and any warnings
 */
class AutoSchedulingService(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig,
    private val defaultsRepository: SeasonScheduleDefaultsRepository,
    private val overridesRepository: MatchdayScheduleOverridesRepository,
    private val dayGroupRepository: DayGroupRepository,
    private val availabilityRepository: PlayerAvailabilityRepository,
    private val categoryRepository: LeagueCategoryRepository
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    /**
     * Auto-schedule all unassigned groups for a matchday
     */
    suspend fun autoSchedule(request: AutoScheduleRequest): AutoScheduleResponse {
        val warnings = mutableListOf<String>()

        // 1. Get schedule configuration (defaults + overrides)
        val defaults = defaultsRepository.getBySeasonId(request.seasonId)
        if (defaults == null) {
            return AutoScheduleResponse(
                totalGroups = 0,
                assignedGroups = 0,
                skippedGroups = 0,
                assignments = emptyList(),
                warnings = listOf("No schedule defaults configured for this season")
            )
        }

        val override = overridesRepository.getBySeasonAndMatchday(request.seasonId, request.matchdayNumber)
        val numberOfCourts = override?.numberOfCourtsOverride ?: defaults.defaultNumberOfCourts
        val timeSlots = override?.timeSlotsOverride ?: defaults.defaultTimeSlots

        if (timeSlots.isEmpty()) {
            return AutoScheduleResponse(
                totalGroups = 0,
                assignedGroups = 0,
                skippedGroups = 0,
                assignments = emptyList(),
                warnings = listOf("No time slots configured")
            )
        }

        // 2. Get all unassigned day groups for this matchday across all categories
        val unassignedGroups = fetchUnassignedGroups(request.seasonId, request.matchdayNumber)
        if (unassignedGroups.isEmpty()) {
            return AutoScheduleResponse(
                totalGroups = 0,
                assignedGroups = 0,
                skippedGroups = 0,
                assignments = emptyList(),
                warnings = listOf("No unassigned groups found for this matchday")
            )
        }

        // 3. Get player availability data
        val playerAvailability = if (request.respectAvailability) {
            availabilityRepository.getBySeasonId(request.seasonId)
                .groupBy { it.playerId }
        } else {
            emptyMap()
        }

        // 4. Build available slots grid
        val availableSlots = mutableSetOf<Slot>()
        for (courtIndex in 1..numberOfCourts) {
            for (timeSlot in timeSlots) {
                availableSlots.add(Slot(request.matchDate, timeSlot, courtIndex))
            }
        }

        // 5. Calculate availability scores for each group-slot combination
        val matchDate = LocalDate.parse(request.matchDate)
        val dayOfWeek = matchDate.dayOfWeek.value % 7 // Convert to 0=Sunday format

        val groupSlotScores = unassignedGroups.map { group ->
            val slotScores = availableSlots.map { slot ->
                val score = calculateAvailabilityScore(
                    group.playerIds,
                    playerAvailability,
                    dayOfWeek,
                    slot.timeSlot
                )
                SlotScore(slot, score.score, score.unavailablePlayers)
            }.sortedByDescending { it.score }

            GroupWithScores(group, slotScores)
        }

        // 6. Sort by MRV - groups with fewer high-scoring slots first
        val sortedGroups = groupSlotScores.sortedBy { groupScores ->
            // Count slots with score >= 0.75 (at least 75% available)
            groupScores.slotScores.count { it.score >= 0.75 }
        }

        // 7. Assign groups using greedy algorithm with MRV ordering
        val assignments = mutableListOf<GroupAssignment>()
        val usedSlots = mutableSetOf<Slot>()
        var skippedGroups = 0

        for (groupWithScores in sortedGroups) {
            val group = groupWithScores.group

            // Find best available slot
            val bestSlot = groupWithScores.slotScores.firstOrNull { slotScore ->
                !usedSlots.contains(slotScore.slot)
            }

            if (bestSlot != null) {
                // Assign the group to this slot
                val updateRequest = UpdateDayGroupAssignmentRequest(
                    matchDate = bestSlot.slot.date,
                    timeSlot = bestSlot.slot.timeSlot,
                    courtIndex = bestSlot.slot.courtIndex
                )

                val updated = dayGroupRepository.updateAssignment(group.id, updateRequest)
                if (updated) {
                    usedSlots.add(bestSlot.slot)
                    assignments.add(
                        GroupAssignment(
                            dayGroupId = group.id,
                            groupNumber = group.groupNumber,
                            categoryName = group.categoryName,
                            matchDate = bestSlot.slot.date,
                            timeSlot = bestSlot.slot.timeSlot,
                            courtIndex = bestSlot.slot.courtIndex,
                            availabilityScore = bestSlot.score,
                            unavailablePlayers = bestSlot.unavailablePlayers
                        )
                    )

                    // Add warning if availability score is low
                    if (bestSlot.score < 1.0 && bestSlot.unavailablePlayers.isNotEmpty()) {
                        warnings.add(
                            "Group ${group.groupNumber} (${group.categoryName}): " +
                            "${bestSlot.unavailablePlayers.size} player(s) may not be available"
                        )
                    }
                } else {
                    skippedGroups++
                    warnings.add("Failed to assign Group ${group.groupNumber} (${group.categoryName})")
                }
            } else {
                skippedGroups++
                warnings.add("No available slots for Group ${group.groupNumber} (${group.categoryName})")
            }
        }

        return AutoScheduleResponse(
            totalGroups = unassignedGroups.size,
            assignedGroups = assignments.size,
            skippedGroups = skippedGroups,
            assignments = assignments,
            warnings = warnings
        )
    }

    /**
     * Calculate availability score for a group at a specific time slot
     * Returns score from 0.0 (no one available) to 1.0 (everyone available)
     */
    private fun calculateAvailabilityScore(
        playerIds: List<String>,
        playerAvailability: Map<String, List<PlayerAvailabilityResponse>>,
        dayOfWeek: Int,
        timeSlot: String
    ): AvailabilityResult {
        if (playerIds.isEmpty()) {
            return AvailabilityResult(1.0, emptyList())
        }

        val unavailablePlayers = mutableListOf<String>()
        var availableCount = 0

        for (playerId in playerIds) {
            val availability = playerAvailability[playerId]
            if (availability == null) {
                // No availability set - assume available
                availableCount++
                continue
            }

            val dayAvailability = availability.find { it.dayOfWeek == dayOfWeek }
            if (dayAvailability == null) {
                // No availability for this day - assume unavailable
                unavailablePlayers.add(playerId)
                continue
            }

            if (dayAvailability.availableTimeSlots.contains(timeSlot)) {
                availableCount++
            } else {
                unavailablePlayers.add(playerId)
            }
        }

        val score = availableCount.toDouble() / playerIds.size
        return AvailabilityResult(score, unavailablePlayers)
    }

    /**
     * Fetch all unassigned day groups for a matchday across all categories in the season
     */
    private suspend fun fetchUnassignedGroups(seasonId: String, matchdayNumber: Int): List<UnassignedGroup> {
        // First get all categories for this season
        val categories = categoryRepository.getBySeason(seasonId)

        val unassignedGroups = mutableListOf<UnassignedGroup>()

        for (category in categories) {
            // Fetch match day for this category and matchday number
            val response = client.get("$apiUrl/match_days") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("select", "*,day_groups(*)")
                parameter("category_id", "eq.${category.id}")
                parameter("match_number", "eq.$matchdayNumber")
            }

            if (response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                val matchDays = json.decodeFromString<List<MatchDayWithGroupsRaw>>(bodyText)

                for (matchDay in matchDays) {
                    for (dayGroup in matchDay.dayGroups) {
                        // Check if unassigned (no match_date, time_slot, or court_index)
                        if (dayGroup.matchDate == null || dayGroup.timeSlot == null || dayGroup.courtIndex == null) {
                            unassignedGroups.add(
                                UnassignedGroup(
                                    id = dayGroup.id,
                                    groupNumber = dayGroup.groupNumber,
                                    categoryId = category.id,
                                    categoryName = category.name,
                                    playerIds = dayGroup.playerIds
                                )
                            )
                        }
                    }
                }
            }
        }

        return unassignedGroups
    }

    // Helper data classes
    private data class Slot(
        val date: String,
        val timeSlot: String,
        val courtIndex: Int
    )

    private data class SlotScore(
        val slot: Slot,
        val score: Double,
        val unavailablePlayers: List<String>
    )

    private data class GroupWithScores(
        val group: UnassignedGroup,
        val slotScores: List<SlotScore>
    )

    private data class UnassignedGroup(
        val id: String,
        val groupNumber: Int,
        val categoryId: String,
        val categoryName: String,
        val playerIds: List<String>
    )

    private data class AvailabilityResult(
        val score: Double,
        val unavailablePlayers: List<String>
    )
}

// Raw response for deserialization (matches MasterScheduleService)
@Serializable
private data class MatchDayWithGroupsRaw(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<DayGroupRaw>
)

@Serializable
private data class DayGroupRaw(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String
)
