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
}

sealed class RegenerateResult {
    object Success : RegenerateResult()
    data class Error(val message: String) : RegenerateResult()
    object AlreadyExists : RegenerateResult()
    object NotEnoughPlayers : RegenerateResult()
}
