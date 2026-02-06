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
 * 4. Sort groups by:
 *    a) Category level (1st category first, then 2nd, etc.) - higher priority categories get first pick
 *    b) Within same category, MRV (groups with fewer perfect slots first)
 * 5. Assign groups to their best available slot, prioritizing recommended courts
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
    private val categoryRepository: LeagueCategoryRepository,
    private val courtRepository: SeasonCourtRepository
) {
    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    /**
     * Auto-schedule all unassigned groups for a matchday
     * Supports per-category dates via categoryDates map
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

        // 2. Get all day groups for this matchday (unassigned + already-used slots)
        val groupsAndSlots = fetchGroupsAndUsedSlots(request.seasonId, request.matchdayNumber)
        val allUnassignedGroups = groupsAndSlots.unassignedGroups
        val preExistingUsedSlots = groupsAndSlots.usedSlotsByDate

        // 2.5. Build category-to-date mapping
        // Priority: categoryDates > categoryIds with matchDate > matchDate for all
        val categoryDateMap: Map<String, String> = when {
            // New per-category dates mode
            request.categoryDates != null && request.categoryDates.isNotEmpty() -> {
                request.categoryDates
            }
            // Legacy mode: categoryIds with single matchDate
            request.categoryIds != null && request.categoryIds.isNotEmpty() && request.matchDate != null -> {
                request.categoryIds.associateWith { request.matchDate }
            }
            // Legacy mode: all categories with single matchDate
            request.matchDate != null -> {
                allUnassignedGroups.map { it.categoryId }.distinct().associateWith { request.matchDate }
            }
            else -> {
                return AutoScheduleResponse(
                    totalGroups = 0,
                    assignedGroups = 0,
                    skippedGroups = 0,
                    assignments = emptyList(),
                    warnings = listOf("No match date provided. Use either matchDate or categoryDates.")
                )
            }
        }

        // Filter groups to only those with dates assigned
        val unassignedGroups = allUnassignedGroups.filter { it.categoryId in categoryDateMap.keys }

        if (unassignedGroups.isEmpty()) {
            return AutoScheduleResponse(
                totalGroups = 0,
                assignedGroups = 0,
                skippedGroups = 0,
                assignments = emptyList(),
                warnings = listOf("No unassigned groups found for the selected categories")
            )
        }

        // 3. Get player availability data for this specific matchday
        val playerAvailability = if (request.respectAvailability) {
            availabilityRepository.getBySeasonAndMatchday(request.seasonId, request.matchdayNumber)
                .groupBy { it.playerId }
        } else {
            emptyMap()
        }

        // 3.5. Build player ID to name map for better warnings
        val playerNameMap = buildPlayerNameMap(request.seasonId)

        // 3.6. Get historical time slots for players (for variety scoring)
        val playerTimeSlotHistory = if (request.preferTimeSlotVariety && request.matchdayNumber > 1) {
            fetchPlayerTimeSlotHistory(request.seasonId, request.matchdayNumber)
        } else {
            null
        }

        // 4. Group categories by date (categories on same date share court slots)
        val categoriesByDate = categoryDateMap.entries.groupBy({ it.value }, { it.key })

        // Track used slots per date (different dates don't share slots)
        // Pre-populate with already-assigned slots to avoid overlaps
        val usedSlotsByDate = mutableMapOf<String, MutableSet<Slot>>()
        for ((date, slots) in preExistingUsedSlots) {
            usedSlotsByDate[date] = slots.toMutableSet()
        }
        val allAssignments = mutableListOf<GroupAssignment>()
        val allSkippedDetails = mutableListOf<String>()
        var totalSkipped = 0

        // 4.5. Fetch courts for the season (active only)
        val seasonCourts = courtRepository.getBySeasonId(request.seasonId)
            .filter { it.isActive }
            .sortedBy { it.courtNumber }

        if (seasonCourts.isEmpty()) {
            return AutoScheduleResponse(
                totalGroups = unassignedGroups.size,
                assignedGroups = 0,
                skippedGroups = unassignedGroups.size,
                assignments = emptyList(),
                warnings = listOf("No active courts configured for this season")
            )
        }

        // 4.6. Process each date separately
        for ((matchDate, categoryIdsForDate) in categoriesByDate) {
            // Build available slots for this date using actual court records
            // NOTE: court.courtNumber is stored as court_index in day_groups.
            // This assumes court numbers are sequential (1, 2, 3...). If courts
            // ever have non-sequential numbering (e.g., 1, 3, 5 after deletions),
            // the iOS grid display will break since it uses 1..N range.
            val availableSlots = mutableSetOf<Slot>()
            for (court in seasonCourts) {
                for (timeSlot in timeSlots) {
                    availableSlots.add(Slot(matchDate, timeSlot, court.courtNumber, court.id, court.name))
                }
            }

            // Initialize used slots tracking for this date
            if (usedSlotsByDate[matchDate] == null) {
                usedSlotsByDate[matchDate] = mutableSetOf()
            }
            val usedSlots = usedSlotsByDate[matchDate]!!

            // Get groups for this date's categories
            val groupsForDate = unassignedGroups.filter { it.categoryId in categoryIdsForDate }

            if (groupsForDate.isEmpty()) continue

            // 5. Calculate availability scores for each group-slot combination
            val groupSlotScores = groupsForDate.map { group ->
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

            // 6. Sort groups by:
            //    a) Category level (1st category first, then 2nd, etc.) - higher priority categories get first pick
            //    b) Within same category, MRV (groups with fewer perfect slots first)
            val sortedGroups = groupSlotScores.sortedWith(
                compareBy(
                    // Primary: category level (lower = higher priority, e.g., 1ra before 7ma)
                    { it.group.categoryLevel },
                    // Secondary: MRV - fewer perfect slots = more constrained = higher priority
                    { it.slotScores.count { slot -> slot.score == 1.0 } }
                )
            )

            // 7. Assign groups using greedy algorithm with MRV ordering
            for (groupWithScores in sortedGroups) {
                val group = groupWithScores.group
                val recommendedCourts = group.recommendedCourts

                // Find available slots not yet used on this date
                val remainingSlots = groupWithScores.slotScores.filter { slotScore ->
                    !usedSlots.contains(slotScore.slot)
                }

                // Separate perfect slots (100% availability) from partial slots
                val perfectSlots = remainingSlots.filter { it.score == 1.0 }
                val partialSlots = remainingSlots.filter { it.score < 1.0 }.sortedByDescending { it.score }

                // Calculate variety scores for perfect slots if enabled
                val perfectSlotsWithVariety = if (request.preferTimeSlotVariety && playerTimeSlotHistory != null) {
                    perfectSlots.map { slotScore ->
                        val varietyScore = calculateVarietyScore(
                            group.playerIds,
                            slotScore.slot.timeSlot,
                            playerTimeSlotHistory
                        )
                        SlotWithVariety(slotScore, varietyScore)
                    }.sortedByDescending { it.varietyScore }
                } else {
                    perfectSlots.map { SlotWithVariety(it, 1.0) }
                }

                // Try to find a perfect slot first
                val perfectSlot = if (recommendedCourts != null && recommendedCourts.isNotEmpty()) {
                    // First try to find a perfect slot on a recommended court with best variety
                    perfectSlotsWithVariety.firstOrNull { it.slotScore.slot.courtIndex in recommendedCourts }?.slotScore
                        // Fall back to best variety slot regardless of court
                        ?: perfectSlotsWithVariety.firstOrNull()?.slotScore
                } else {
                    // Pick slot with best variety score
                    perfectSlotsWithVariety.firstOrNull()?.slotScore
                }

                if (perfectSlot != null) {
                    // Assign the group to this slot - all players are available
                    val updateRequest = UpdateDayGroupAssignmentRequest(
                        matchDate = perfectSlot.slot.date,
                        timeSlot = perfectSlot.slot.timeSlot,
                        courtIndex = perfectSlot.slot.courtIndex,
                        courtId = perfectSlot.slot.courtId
                    )

                    val updated = dayGroupRepository.updateAssignment(group.id, updateRequest)
                    if (updated) {
                        usedSlots.add(perfectSlot.slot)
                        allAssignments.add(
                            GroupAssignment(
                                dayGroupId = group.id,
                                groupNumber = group.groupNumber,
                                categoryName = group.categoryName,
                                matchDate = perfectSlot.slot.date,
                                timeSlot = perfectSlot.slot.timeSlot,
                                courtIndex = perfectSlot.slot.courtIndex,
                                courtName = perfectSlot.slot.courtName,
                                availabilityScore = perfectSlot.score,
                                unavailablePlayers = emptyList()
                            )
                        )
                    } else {
                        totalSkipped++
                        allSkippedDetails.add("Grupo ${group.groupNumber} (${group.categoryName}): Error al guardar asignación")
                    }
                } else if (!request.strictMode && partialSlots.isNotEmpty()) {
                    // FLEXIBLE MODE: Assign even with partial availability
                    // Pick the best partial slot (highest availability score)
                    val bestPartialSlot = if (recommendedCourts != null && recommendedCourts.isNotEmpty()) {
                        partialSlots.firstOrNull { it.slot.courtIndex in recommendedCourts }
                            ?: partialSlots.first()
                    } else {
                        partialSlots.first()
                    }

                    val updateRequest = UpdateDayGroupAssignmentRequest(
                        matchDate = bestPartialSlot.slot.date,
                        timeSlot = bestPartialSlot.slot.timeSlot,
                        courtIndex = bestPartialSlot.slot.courtIndex,
                        courtId = bestPartialSlot.slot.courtId
                    )

                    val updated = dayGroupRepository.updateAssignment(group.id, updateRequest)
                    if (updated) {
                        usedSlots.add(bestPartialSlot.slot)

                        // Map unavailable player IDs to names for the response
                        val unavailableNames = bestPartialSlot.unavailablePlayers
                            .map { playerId -> playerNameMap[playerId] ?: playerId }

                        allAssignments.add(
                            GroupAssignment(
                                dayGroupId = group.id,
                                groupNumber = group.groupNumber,
                                categoryName = group.categoryName,
                                matchDate = bestPartialSlot.slot.date,
                                timeSlot = bestPartialSlot.slot.timeSlot,
                                courtIndex = bestPartialSlot.slot.courtIndex,
                                courtName = bestPartialSlot.slot.courtName,
                                availabilityScore = bestPartialSlot.score,
                                unavailablePlayers = unavailableNames
                            )
                        )

                        // Add warning about the conflict
                        val unavailableWarning = unavailableNames.joinToString(", ")
                        val availabilityPercent = (bestPartialSlot.score * 100).toInt()
                        allSkippedDetails.add(
                            "⚠️ Grupo ${group.groupNumber} (${group.categoryName}): " +
                            "Asignado con ${availabilityPercent}% disponibilidad. " +
                            "No disponibles: $unavailableWarning"
                        )
                    } else {
                        totalSkipped++
                        allSkippedDetails.add("Grupo ${group.groupNumber} (${group.categoryName}): Error al guardar asignación")
                    }
                } else {
                    // STRICT MODE or no slots available: Leave unassigned for manual scheduling
                    totalSkipped++

                    // Find the best partial slot to explain why it couldn't be assigned
                    val bestPartialSlot = partialSlots.firstOrNull()

                    if (bestPartialSlot != null && bestPartialSlot.unavailablePlayers.isNotEmpty()) {
                        val unavailableNames = bestPartialSlot.unavailablePlayers
                            .take(4)
                            .map { playerId -> playerNameMap[playerId] ?: playerId }
                            .joinToString(", ")
                        allSkippedDetails.add(
                            "Grupo ${group.groupNumber} (${group.categoryName}): " +
                            "Sin horario común disponible. Jugadores no disponibles: $unavailableNames"
                        )
                    } else if (remainingSlots.isEmpty()) {
                        allSkippedDetails.add(
                            "Grupo ${group.groupNumber} (${group.categoryName}): " +
                            "Todos los horarios ya están ocupados"
                        )
                    } else {
                        allSkippedDetails.add(
                            "Grupo ${group.groupNumber} (${group.categoryName}): " +
                            "Sin horario común disponible para los 4 jugadores"
                        )
                    }
                }
            } // End of groups loop
        } // End of dates loop

        // Add all skipped group details as warnings
        warnings.addAll(allSkippedDetails)

        return AutoScheduleResponse(
            totalGroups = unassignedGroups.size,
            assignedGroups = allAssignments.size,
            skippedGroups = totalSkipped,
            assignments = allAssignments,
            warnings = warnings
        )
    }

    /**
     * Preview auto-scheduling without making any changes (dry-run).
     * Returns availability analysis for the matchday.
     */
    suspend fun preview(request: AutoSchedulePreviewRequest): AutoSchedulePreviewResponse {
        // 1. Get schedule configuration (defaults + overrides)
        // Check BOTH sources - matchday override takes priority, but we can work with either
        val defaults = defaultsRepository.getBySeasonId(request.seasonId)
        val override = overridesRepository.getBySeasonAndMatchday(request.seasonId, request.matchdayNumber)

        // Determine time slots: override takes priority, then defaults
        val timeSlots = when {
            override?.timeSlotsOverride != null && override.timeSlotsOverride.isNotEmpty() -> override.timeSlotsOverride
            defaults?.defaultTimeSlots != null && defaults.defaultTimeSlots.isNotEmpty() -> defaults.defaultTimeSlots
            else -> emptyList()
        }

        if (timeSlots.isEmpty()) {
            return AutoSchedulePreviewResponse(
                totalGroups = 0,
                groupsFullAvailability = 0,
                groupsPartialAvailability = 0,
                groupsNoAvailability = 0,
                timeSlotAvailability = emptyList(),
                categoryPreviews = emptyList()
            )
        }

        // 2. Get all unassigned day groups for this matchday
        val groupsAndSlots = fetchGroupsAndUsedSlots(request.seasonId, request.matchdayNumber)
        val allUnassignedGroups = groupsAndSlots.unassignedGroups

        // Filter by categoryIds if specified
        val unassignedGroups = if (request.categoryIds != null && request.categoryIds.isNotEmpty()) {
            allUnassignedGroups.filter { it.categoryId in request.categoryIds }
        } else {
            allUnassignedGroups
        }

        if (unassignedGroups.isEmpty()) {
            return AutoSchedulePreviewResponse(
                totalGroups = 0,
                groupsFullAvailability = 0,
                groupsPartialAvailability = 0,
                groupsNoAvailability = 0,
                timeSlotAvailability = emptyList(),
                categoryPreviews = emptyList()
            )
        }

        // 3. Get player availability data for this matchday
        val playerAvailability = availabilityRepository.getBySeasonAndMatchday(request.seasonId, request.matchdayNumber)
            .groupBy { it.playerId }

        // 4. Collect all unique player IDs across all groups
        val allPlayerIds = unassignedGroups.flatMap { it.playerIds }.toSet()

        // 5. Calculate time slot availability
        val timeSlotAvailabilityList = timeSlots.map { timeSlot ->
            var availableCount = 0
            for (playerId in allPlayerIds) {
                val availability = playerAvailability[playerId]
                if (availability == null || availability.isEmpty()) {
                    // No availability set - assume available
                    availableCount++
                } else {
                    val matchdayAvailability = availability.firstOrNull()
                    if (matchdayAvailability != null && matchdayAvailability.availableTimeSlots.contains(timeSlot)) {
                        availableCount++
                    }
                }
            }
            val percentage = if (allPlayerIds.isNotEmpty()) {
                (availableCount.toDouble() / allPlayerIds.size) * 100
            } else {
                0.0
            }
            TimeSlotAvailabilityInfo(
                timeSlot = timeSlot,
                availablePlayers = availableCount,
                totalPlayers = allPlayerIds.size,
                availabilityPercentage = percentage
            )
        }

        // 6. Calculate per-group availability and categorize
        var groupsFullAvailability = 0
        var groupsPartialAvailability = 0
        var groupsNoAvailability = 0

        // Group by category for category previews
        val categoryGroupMap = unassignedGroups.groupBy { it.categoryId to it.categoryName }
        val categoryPreviews = mutableListOf<CategoryPreviewInfo>()

        for ((categoryKey, groups) in categoryGroupMap) {
            val (categoryId, categoryName) = categoryKey
            var catFullAvail = 0
            var catPartialAvail = 0
            var catNoAvail = 0

            for (group in groups) {
                // Check if this group has ANY time slot where all players are available
                var hasFullSlot = false
                var hasPartialSlot = false

                for (timeSlot in timeSlots) {
                    val result = calculateAvailabilityScore(group.playerIds, playerAvailability, timeSlot)
                    if (result.score == 1.0) {
                        hasFullSlot = true
                        break
                    } else if (result.score > 0.0) {
                        hasPartialSlot = true
                    }
                }

                if (hasFullSlot) {
                    groupsFullAvailability++
                    catFullAvail++
                } else if (hasPartialSlot) {
                    groupsPartialAvailability++
                    catPartialAvail++
                } else {
                    groupsNoAvailability++
                    catNoAvail++
                }
            }

            val catTotalGroups = groups.size
            val catAvailPercentage = if (catTotalGroups > 0) {
                ((catFullAvail + catPartialAvail).toDouble() / catTotalGroups) * 100
            } else {
                0.0
            }

            categoryPreviews.add(
                CategoryPreviewInfo(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    totalGroups = catTotalGroups,
                    groupsFullAvailability = catFullAvail,
                    groupsPartialAvailability = catPartialAvail,
                    groupsNoAvailability = catNoAvail,
                    availabilityPercentage = catAvailPercentage
                )
            )
        }

        return AutoSchedulePreviewResponse(
            totalGroups = unassignedGroups.size,
            groupsFullAvailability = groupsFullAvailability,
            groupsPartialAvailability = groupsPartialAvailability,
            groupsNoAvailability = groupsNoAvailability,
            timeSlotAvailability = timeSlotAvailabilityList,
            categoryPreviews = categoryPreviews.sortedBy { it.categoryName }
        )
    }

    /**
     * Calculate availability score for a group at a specific time slot
     * Returns score from 0.0 (no one available) to 1.0 (everyone available)
     *
     * Uses matchday-specific availability.
     * If a player hasn't set availability for this matchday, they're assumed available.
     */
    private fun calculateAvailabilityScore(
        playerIds: List<String>,
        playerAvailability: Map<String, List<PlayerAvailabilityResponse>>,
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
     * Fetch all day groups for a matchday, returning both unassigned groups and already-used slots.
     * This ensures we don't create overlapping assignments with existing ones.
     * Batched: 1 query for categories + 1 query for all match days across all categories.
     */
    private suspend fun fetchGroupsAndUsedSlots(seasonId: String, matchdayNumber: Int): GroupsAndSlots {
        // 1 query: get all categories for this season
        val categories = categoryRepository.getBySeasonId(seasonId)
        if (categories.isEmpty()) return GroupsAndSlots(emptyList(), emptyMap())

        val categoryIds = categories.map { it.id }
        val categoryMap = categories.associateBy { it.id }

        // 1 query: fetch all match days for all categories in a single batch
        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "*,day_groups(*,season_courts(id,name,court_number))")
            parameter("category_id", "in.(${categoryIds.joinToString(",")})")
            parameter("match_number", "eq.$matchdayNumber")
        }

        if (!response.status.isSuccess()) return GroupsAndSlots(emptyList(), emptyMap())

        val matchDays = json.decodeFromString<List<AutoScheduleMatchDayRaw>>(response.bodyAsText())

        val unassignedGroups = mutableListOf<UnassignedGroup>()
        val usedSlots = mutableMapOf<String, MutableSet<Slot>>()

        for (matchDay in matchDays) {
            val category = categoryMap[matchDay.categoryId] ?: continue
            val categoryLevel = category.level.toIntOrNull() ?: 99

            for (dayGroup in matchDay.dayGroups) {
                if (dayGroup.matchDate == null || dayGroup.timeSlot == null || dayGroup.courtIndex == null) {
                    unassignedGroups.add(
                        UnassignedGroup(
                            id = dayGroup.id,
                            groupNumber = dayGroup.groupNumber,
                            categoryId = category.id,
                            categoryName = category.name,
                            categoryLevel = categoryLevel,
                            playerIds = dayGroup.playerIds,
                            recommendedCourts = category.recommendedCourts
                        )
                    )
                } else {
                    val courtId = dayGroup.courtId ?: dayGroup.seasonCourt?.id ?: ""
                    val courtName = dayGroup.seasonCourt?.name ?: "Cancha ${dayGroup.courtIndex}"
                    val slot = Slot(dayGroup.matchDate, dayGroup.timeSlot, dayGroup.courtIndex, courtId, courtName)
                    usedSlots.getOrPut(dayGroup.matchDate) { mutableSetOf() }.add(slot)
                }
            }
        }

        return GroupsAndSlots(unassignedGroups, usedSlots)
    }

    /**
     * Data class to return both unassigned groups and already-used slots
     */
    private data class GroupsAndSlots(
        val unassignedGroups: List<UnassignedGroup>,
        val usedSlotsByDate: Map<String, Set<Slot>>
    )

    /**
     * Build a map of player ID to player name for all players in the season.
     * Single query using category_id=in.(...) instead of N per-category queries.
     */
    private suspend fun buildPlayerNameMap(seasonId: String): Map<String, String> {
        val categories = categoryRepository.getBySeasonId(seasonId)
        if (categories.isEmpty()) return emptyMap()

        val categoryIds = categories.map { it.id }

        val response = client.get("$apiUrl/league_players") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id,name")
            parameter("category_id", "in.(${categoryIds.joinToString(",")})")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString<List<PlayerNameOnly>>(response.bodyAsText())
                .associate { it.id to it.name }
        } else {
            emptyMap()
        }
    }

    /**
     * Fetch historical time slot data for all players in the season from previous matchdays.
     * Single query using category_id=in.(...) and match_number=lt.N instead of N×M queries.
     * Returns both:
     * - Overall frequency: map of playerId to map of timeSlot to count
     * - Previous matchday slots: map of playerId to the time slot they played in the immediately previous matchday
     */
    private suspend fun fetchPlayerTimeSlotHistory(
        seasonId: String,
        currentMatchdayNumber: Int
    ): PlayerTimeSlotHistoryData {
        val categories = categoryRepository.getBySeasonId(seasonId)
        if (categories.isEmpty()) return PlayerTimeSlotHistoryData(emptyMap(), emptyMap())

        val categoryIds = categories.map { it.id }
        val previousMatchdayNumber = currentMatchdayNumber - 1

        // 1 query: fetch all previous match days across all categories with day_groups
        val response = client.get("$apiUrl/match_days") {
            header("apikey", apiKey)
            header("Authorization", "Bearer $apiKey")
            parameter("select", "id,match_number,day_groups(player_ids,time_slot)")
            parameter("category_id", "in.(${categoryIds.joinToString(",")})")
            parameter("match_number", "lt.$currentMatchdayNumber")
        }

        if (!response.status.isSuccess()) return PlayerTimeSlotHistoryData(emptyMap(), emptyMap())

        val matchDays = json.decodeFromString<List<AutoScheduleMatchDayRaw>>(response.bodyAsText())

        val overallFrequency = mutableMapOf<String, MutableMap<String, Int>>()
        val previousMatchdaySlots = mutableMapOf<String, String>()

        for (matchDay in matchDays) {
            for (dayGroup in matchDay.dayGroups) {
                val timeSlot = dayGroup.timeSlot ?: continue
                for (playerId in dayGroup.playerIds) {
                    // Track overall frequency
                    val playerSlots = overallFrequency.getOrPut(playerId) { mutableMapOf() }
                    playerSlots[timeSlot] = (playerSlots[timeSlot] ?: 0) + 1

                    // Track previous matchday slot specifically
                    if (matchDay.matchNumber == previousMatchdayNumber) {
                        previousMatchdaySlots[playerId] = timeSlot
                    }
                }
            }
        }

        return PlayerTimeSlotHistoryData(overallFrequency, previousMatchdaySlots)
    }

    /**
     * Calculate variety score for a group at a specific time slot.
     * Uses FREQUENCY-based scoring with RECENCY penalty.
     *
     * Scoring factors:
     * 1. Overall frequency: prefer slots where players have played fewer times overall
     * 2. Previous matchday penalty: heavily penalize if players played at this same slot last matchday
     *
     * Returns a score where higher = better variety.
     */
    private fun calculateVarietyScore(
        playerIds: List<String>,
        timeSlot: String,
        historyData: PlayerTimeSlotHistoryData
    ): Double {
        if (playerIds.isEmpty()) return 1.0

        // Factor 1: Sum up how many times all players have played at this time slot overall
        var totalPlaysAtSlot = 0
        for (playerId in playerIds) {
            val playerSlotCounts = historyData.overallFrequency[playerId]
            if (playerSlotCounts != null) {
                totalPlaysAtSlot += playerSlotCounts[timeSlot] ?: 0
            }
        }

        // Factor 2: Count how many players played at this SAME time slot in the previous matchday
        var playersAtSameSlotLastMatchday = 0
        for (playerId in playerIds) {
            val lastSlot = historyData.previousMatchdaySlots[playerId]
            if (lastSlot == timeSlot) {
                playersAtSameSlotLastMatchday++
            }
        }

        // Calculate base frequency score: fewer plays = higher score
        // Score of 1.0 for 0 plays, decreasing as plays increase
        val frequencyScore = 1.0 / (1.0 + totalPlaysAtSlot)

        // Apply recency penalty: heavily penalize if players played same slot last matchday
        // Penalty multiplier: 1.0 if no one played here last time, down to 0.1 if all 4 did
        val recencyPenalty = 1.0 - (playersAtSameSlotLastMatchday.toDouble() / playerIds.size * 0.9)

        // Combine: frequency score × recency penalty
        // This ensures we strongly prefer slots that are DIFFERENT from last matchday
        return frequencyScore * recencyPenalty
    }

    /**
     * Data class holding player time slot history
     */
    private data class PlayerTimeSlotHistoryData(
        val overallFrequency: Map<String, Map<String, Int>>,  // playerId -> (timeSlot -> count)
        val previousMatchdaySlots: Map<String, String>         // playerId -> timeSlot from previous matchday
    )

    // Helper data classes
    // Slot represents a physical court slot; equality is based on date/timeSlot/courtIndex only
    private data class Slot(
        val date: String,
        val timeSlot: String,
        val courtIndex: Int,
        val courtId: String,
        val courtName: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Slot) return false
            return date == other.date && timeSlot == other.timeSlot && courtIndex == other.courtIndex
        }

        override fun hashCode(): Int {
            var result = date.hashCode()
            result = 31 * result + timeSlot.hashCode()
            result = 31 * result + courtIndex
            return result
        }
    }

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
        val categoryLevel: Int, // 1 = highest priority, 7 = lowest
        val playerIds: List<String>,
        val recommendedCourts: List<Int>? = null
    )

    private data class AvailabilityResult(
        val score: Double,
        val unavailablePlayers: List<String>
    )

    private data class SlotWithVariety(
        val slotScore: SlotScore,
        val varietyScore: Double
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
    @SerialName("court_id") val courtId: String? = null,
    @SerialName("season_courts") val seasonCourt: AutoScheduleCourtRaw? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class AutoScheduleCourtRaw(
    val id: String,
    val name: String,
    @SerialName("court_number") val courtNumber: Int
)

@Serializable
private data class PlayerNameOnly(
    val id: String,
    val name: String
)
