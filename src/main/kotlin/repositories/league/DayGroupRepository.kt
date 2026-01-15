package repositories.league

import models.league.DayGroupResponse
import models.league.UpdateDayGroupAssignmentRequest

interface DayGroupRepository {
    suspend fun getByMatchDayId(matchDayId: String): List<DayGroupResponse>
    suspend fun getById(id: String): DayGroupResponse?
    suspend fun updateAssignment(id: String, request: UpdateDayGroupAssignmentRequest): Boolean
    suspend fun getRotationCount(dayGroupId: String): Int
    suspend fun regenerateRotations(dayGroupId: String): RegenerateResult

    /**
     * Find a day group occupying a specific slot (date + time + court)
     * Used for detecting conflicts and enabling swaps
     */
    suspend fun findBySlot(matchDate: String, timeSlot: String, courtIndex: Int): DayGroupResponse?

    /**
     * Phase 3.1: Clear all assignments for a matchday
     * Sets match_date, time_slot, court_index to NULL for all day_groups in the matchday
     * Returns the count of groups that were cleared
     */
    suspend fun clearMatchdayAssignments(seasonId: String, matchdayNumber: Int): Int
}

sealed class RegenerateResult {
    object Success : RegenerateResult()
    data class Error(val message: String) : RegenerateResult()
    object AlreadyExists : RegenerateResult()
    object NotEnoughPlayers : RegenerateResult()
}
