package repositories.ranking

import models.ranking.PointsConfigResponse

interface PointsConfigRepository {
    suspend fun getAllByOrganizer(organizerId: String): List<PointsConfigResponse>
    suspend fun getById(id: String): PointsConfigResponse?
    suspend fun getEffective(
        organizerId: String,
        tournamentId: String?,
        tournamentType: String,
        stage: String
    ): PointsConfigResponse?
    suspend fun create(organizerId: String, body: String): PointsConfigResponse
    suspend fun update(id: String, body: String): PointsConfigResponse?
    suspend fun delete(id: String): Boolean
}
