package repositories.league

import models.league.RotationResponse

interface RotationRepository {
    suspend fun getByDayGroupId(dayGroupId: String): List<RotationResponse>
    suspend fun getById(id: String): RotationResponse?
}
