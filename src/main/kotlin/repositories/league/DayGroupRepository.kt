package repositories.league

import models.league.DayGroupResponse
import models.league.UpdateDayGroupAssignmentRequest

interface DayGroupRepository {
    suspend fun getByMatchDayId(matchDayId: String): List<DayGroupResponse>
    suspend fun getById(id: String): DayGroupResponse?
    suspend fun updateAssignment(id: String, request: UpdateDayGroupAssignmentRequest): Boolean
}
