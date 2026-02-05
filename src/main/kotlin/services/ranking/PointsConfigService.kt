package services.ranking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import models.ranking.*
import repositories.ranking.PointsConfigRepository

class PointsConfigService(
    private val repository: PointsConfigRepository,
    private val json: Json
) {

    suspend fun getAllByOrganizer(organizerId: String): List<PointsConfigResponse> {
        return repository.getAllByOrganizer(organizerId)
    }

    suspend fun getEffective(
        organizerId: String,
        tournamentId: String?,
        tournamentType: String,
        stage: String
    ): PointsConfigResponse? {
        return repository.getEffective(organizerId, tournamentId, tournamentType, stage)
    }

    suspend fun create(organizerId: String, request: CreatePointsConfigRequest): PointsConfigResponse {
        // Check if there's already an active config for this type/stage
        val existing = repository.getEffective(
            organizerId,
            request.tournamentId,
            request.tournamentType,
            request.stage
        )
        val shouldActivate = existing == null

        val body = buildJsonObject {
            put("organizer_id", organizerId)
            if (request.tournamentId != null) put("tournament_id", request.tournamentId)
            put("name", request.name)
            put("tournament_type", request.tournamentType)
            put("stage", request.stage)
            put("is_active", shouldActivate)
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
            put("updated_at", "now()")
        }.toString()

        return repository.update(id, body)
    }

    suspend fun delete(id: String): Boolean {
        return repository.delete(id)
    }

    suspend fun activate(organizerId: String, id: String): Boolean {
        // Get the config to find its type/stage
        val config = repository.getById(id)
            ?: return false

        // Verify it belongs to this organizer
        if (config.organizerId != organizerId) return false

        // Deactivate all configs of this type/stage for this organizer
        repository.deactivateByType(organizerId, config.tournamentType, config.stage)

        // Activate the target config
        return repository.activate(id)
    }
}
