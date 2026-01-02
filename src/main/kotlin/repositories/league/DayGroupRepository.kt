package repositories.league

import models.league.DayGroupResponse

interface DayGroupRepository {
    suspend fun getByMatchDayId(matchDayId: String): List<DayGroupResponse>
    suspend fun getById(id: String): DayGroupResponse?
}
