package repositories.league

import models.league.DayGroupResponse
import models.league.UpdateDayGroupAssignmentRequest

interface DayGroupRepository {
    suspend fun getByMatchDayId(matchDayId: String): List<DayGroupResponse>
    suspend fun getById(id: String): DayGroupResponse?
    suspend fun updateAssignment(id: String, request: UpdateDayGroupAssignmentRequest): Boolean
    suspend fun getRotationCount(dayGroupId: String): Int
    suspend fun regenerateRotations(dayGroupId: String): RegenerateResult
}

sealed class RegenerateResult {
    object Success : RegenerateResult()
    data class Error(val message: String) : RegenerateResult()
    object AlreadyExists : RegenerateResult()
    object NotEnoughPlayers : RegenerateResult()
}
