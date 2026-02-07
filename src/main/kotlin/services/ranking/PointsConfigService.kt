package services.ranking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.ranking.*
import repositories.ranking.PointsConfigRepository

class PointsConfigService(
    private val repository: PointsConfigRepository,
    private val json: Json
) {

    suspend fun getAllByOrganizer(organizerId: String): List<PointsConfigResponse> {
        return repository.getAllByOrganizer(organizerId)
    }

    suspend fun getById(id: String): PointsConfigResponse? {
        return repository.getById(id)
    }

    suspend fun create(organizerId: String, request: CreatePointsConfigRequest): PointsConfigResponse {
        val body = buildJsonObject {
            put("organizer_id", organizerId)
            put("name", request.name)
            put("distribution", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(PointDistributionItem.serializer()),
                request.distribution
            ))
        }.toString()

        return repository.create(organizerId, body)
    }

    suspend fun update(id: String, request: UpdatePointsConfigRequest): PointsConfigResponse? {
        val body = buildJsonObject {
            if (request.name != null) put("name", request.name)
            if (request.distribution != null) {
                put("distribution", json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(PointDistributionItem.serializer()),
                    request.distribution
                ))
            }
        }.toString()

        return repository.update(id, body)
    }

    suspend fun delete(id: String): Boolean {
        return repository.delete(id)
    }
}
