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
    private val matchdayAvailabilityRepository: PlayerMatchdayAvailabilityRepository,
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

        // 3. Get player availability data for this specific matchday
        val playerAvailability = if (request.respectAvailability) {
            matchdayAvailabilityRepository.getBySeasonAndMatchday(request.seasonId, request.matchdayNumber)
                .groupBy { it.playerId }
        } else {
            emptyMap()
        }

        // 3.5. Build player ID to name map for better warnings
        val playerNameMap = buildPlayerNameMap(request.seasonId)

        // 4. Build available slots grid
        val availableSlots = mutableSetOf<Slot>()
        for (courtIndex in 1..numberOfCourts) {
            for (timeSlot in timeSlots) {
                availableSlots.add(Slot(request.matchDate, timeSlot, courtIndex))
            }
        }

        // 5. Calculate availability scores for each group-slot combination
        val groupSlotScores = unassignedGroups.map { group ->
            val slotScores = availableSlots.map { slot ->
                val score = calculateAvailabilityScore(
                    group.playerIds,
                    playerAvailability,
                    slot.timeSlot
                )
                SlotScore(slot, score.score, score.unavailablePlayers)
            }.sortedByDescending { it.score }

            GroupWithScores(group, slotScores)
        }

        // 6. Sort by MRV - groups with fewer perfect slots (score = 1.0) first
        // This gives priority to groups with less availability options
        val sortedGroups = groupSlotScores.sortedBy { groupScores ->
            // Count slots where ALL 4 players are available
            groupScores.slotScores.count { it.score == 1.0 }
        }

        // 7. Assign groups using greedy algorithm with MRV ordering
        // IMPORTANT: Only assign if ALL 4 players are available (score = 1.0)
        // Groups without full availability are left unassigned for manual scheduling
        val assignments = mutableListOf<GroupAssignment>()
        val usedSlots = mutableSetOf<Slot>()
        var skippedGroups = 0
        val skippedGroupDetails = mutableListOf<String>()

        for (groupWithScores in sortedGroups) {
            val group = groupWithScores.group
            val recommendedCourts = group.recommendedCourts

            // Find best available slot where ALL players are available (score = 1.0)
            // Prioritize recommended courts if configured
            val perfectSlots = groupWithScores.slotScores.filter { slotScore ->
                !usedSlots.contains(slotScore.slot) && slotScore.score == 1.0
            }

            val perfectSlot = if (recommendedCourts != null && recommendedCourts.isNotEmpty()) {
                // First try to find a perfect slot on a recommended court
                perfectSlots.firstOrNull { it.slot.courtIndex in recommendedCourts }
                    // Fall back to any perfect slot if no recommended court is available
                    ?: perfectSlots.firstOrNull()
            } else {
                perfectSlots.firstOrNull()
            }

            if (perfectSlot != null) {
                // Assign the group to this slot - all players are available
                val updateRequest = UpdateDayGroupAssignmentRequest(
                    matchDate = perfectSlot.slot.date,
                    timeSlot = perfectSlot.slot.timeSlot,
                    courtIndex = perfectSlot.slot.courtIndex
                )

                val updated = dayGroupRepository.updateAssignment(group.id, updateRequest)
                if (updated) {
                    usedSlots.add(perfectSlot.slot)
                    assignments.add(
                        GroupAssignment(
                            dayGroupId = group.id,
                            groupNumber = group.groupNumber,
                            categoryName = group.categoryName,
                            matchDate = perfectSlot.slot.date,
                            timeSlot = perfectSlot.slot.timeSlot,
                            courtIndex = perfectSlot.slot.courtIndex,
                            availabilityScore = perfectSlot.score,
                            unavailablePlayers = emptyList()
                        )
                    )
                } else {
                    skippedGroups++
                    skippedGroupDetails.add("Grupo ${group.groupNumber} (${group.categoryName}): Error al guardar asignación")
                }
            } else {
                // No slot with 100% availability - leave unassigned for manual scheduling
                skippedGroups++

                // Find the best partial slot to explain why it couldn't be assigned
                val bestPartialSlot = groupWithScores.slotScores
                    .filter { !usedSlots.contains(it.slot) }
                    .maxByOrNull { it.score }

                if (bestPartialSlot != null && bestPartialSlot.unavailablePlayers.isNotEmpty()) {
                    val unavailableNames = bestPartialSlot.unavailablePlayers
                        .take(4)
                        .map { playerId -> playerNameMap[playerId] ?: playerId }
                        .joinToString(", ")
                    skippedGroupDetails.add(
                        "Grupo ${group.groupNumber} (${group.categoryName}): " +
                        "Sin horario común disponible. Jugadores no disponibles: $unavailableNames"
                    )
                } else if (groupWithScores.slotScores.all { usedSlots.contains(it.slot) }) {
                    skippedGroupDetails.add(
                        "Grupo ${group.groupNumber} (${group.categoryName}): " +
                        "Todos los horarios ya están ocupados"
                    )
                } else {
                    skippedGroupDetails.add(
                        "Grupo ${group.groupNumber} (${group.categoryName}): " +
                        "Sin horario común disponible para los 4 jugadores"
                    )
                }
            }
        }

        // Add all skipped group details as warnings
        warnings.addAll(skippedGroupDetails)

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
     *
     * Uses matchday-specific availability (not day-of-week).
     * If a player hasn't set availability for this matchday, they're assumed available.
     */
    private fun calculateAvailabilityScore(
        playerIds: List<String>,
        playerAvailability: Map<String, List<PlayerMatchdayAvailabilityResponse>>,
        timeSlot: String
    ): AvailabilityResult {
        if (playerIds.isEmpty()) {
            return AvailabilityResult(1.0, emptyList())
        }

        val unavailablePlayers = mutableListOf<String>()
        var availableCount = 0

        for (playerId in playerIds) {
            val availability = playerAvailability[playerId]
            if (availability == null || availability.isEmpty()) {
                // No availability set for this matchday - assume available
                availableCount++
                continue
            }

            // Player has set availability for this matchday
            // Get their available time slots (should be just one record per matchday)
            val matchdayAvailability = availability.firstOrNull()
            if (matchdayAvailability == null) {
                availableCount++
                continue
            }

            // Check if they're available at this time slot
            if (matchdayAvailability.availableTimeSlots.contains(timeSlot)) {
                availableCount++
            } else {
                // Player explicitly said they're NOT available at this time
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
        val categories = categoryRepository.getBySeasonId(seasonId)

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
                val matchDays = json.decodeFromString<List<AutoScheduleMatchDayRaw>>(bodyText)

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
                                    playerIds = dayGroup.playerIds,
                                    recommendedCourts = category.recommendedCourts
                                )
                            )
                        }
                    }
                }
            }
        }

        return unassignedGroups
    }

    /**
     * Build a map of player ID to player name for all players in the season
     */
    private suspend fun buildPlayerNameMap(seasonId: String): Map<String, String> {
        val playerNameMap = mutableMapOf<String, String>()

        // Get all categories for the season
        val categories = categoryRepository.getBySeasonId(seasonId)

        for (category in categories) {
            // Fetch players for each category
            val response = client.get("$apiUrl/league_players") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("category_id", "eq.${category.id}")
                parameter("select", "id,name")
            }

            if (response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                val players = json.decodeFromString<List<PlayerNameOnly>>(bodyText)
                for (player in players) {
                    playerNameMap[player.id] = player.name
                }
            }
        }

        return playerNameMap
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
        val playerIds: List<String>,
        val recommendedCourts: List<Int>? = null
    )

    private data class AvailabilityResult(
        val score: Double,
        val unavailablePlayers: List<String>
    )
}

// Raw response for deserialization
@Serializable
private data class AutoScheduleMatchDayRaw(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("day_groups") val dayGroups: List<AutoScheduleDayGroupRaw>
)

@Serializable
private data class AutoScheduleDayGroupRaw(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class PlayerNameOnly(
    val id: String,
    val name: String
)
