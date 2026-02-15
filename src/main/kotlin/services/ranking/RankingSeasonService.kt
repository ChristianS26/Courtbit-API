package services.ranking

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.ranking.CreateRankingSeasonRequest
import models.ranking.RankingSeasonResponse
import models.ranking.UpdateRankingSeasonRequest
import repositories.ranking.RankingSeasonRepository
import java.time.LocalDate

class RankingSeasonService(
    private val repository: RankingSeasonRepository
) {

    suspend fun getAllByOrganizer(organizerId: String): List<RankingSeasonResponse> {
        return repository.getAllByOrganizer(organizerId)
    }

    suspend fun getActiveByOrganizer(organizerId: String): RankingSeasonResponse? {
        return repository.getActiveByOrganizer(organizerId)
    }

    suspend fun create(organizerId: String, request: CreateRankingSeasonRequest): RankingSeasonResponse {
        // Validate dates
        val start = LocalDate.parse(request.startDate)
        val end = LocalDate.parse(request.endDate)
        require(!end.isBefore(start)) { "End date must be on or after start date" }

        // Close any currently active season for this organizer
        val activeSeason = repository.getActiveByOrganizer(organizerId)
        if (activeSeason != null) {
            val closeBody = buildJsonObject {
                put("status", "closed")
                put("updated_at", "now()")
            }.toString()
            repository.update(activeSeason.id, closeBody)
        }

        // Create new season as active
        val body = buildJsonObject {
            put("organizer_id", organizerId)
            put("name", request.name)
            put("start_date", request.startDate)
            put("end_date", request.endDate)
            put("status", "active")
        }.toString()

        return repository.create(body)
    }

    suspend fun update(id: String, organizerId: String, request: UpdateRankingSeasonRequest): RankingSeasonResponse? {
        // Verify ownership
        val existing = repository.getById(id) ?: return null
        if (existing.organizerId != organizerId) return null

        // Validate dates if both provided
        val startStr = request.startDate ?: existing.startDate
        val endStr = request.endDate ?: existing.endDate
        val start = LocalDate.parse(startStr)
        val end = LocalDate.parse(endStr)
        require(!end.isBefore(start)) { "End date must be on or after start date" }

        val body = buildJsonObject {
            request.name?.let { put("name", it) }
            request.startDate?.let { put("start_date", it) }
            request.endDate?.let { put("end_date", it) }
            put("updated_at", "now()")
        }.toString()

        return repository.update(id, body)
    }

    suspend fun close(id: String, organizerId: String): Boolean {
        val existing = repository.getById(id) ?: return false
        if (existing.organizerId != organizerId) return false
        if (existing.status != "active") return false

        val body = buildJsonObject {
            put("status", "closed")
            put("updated_at", "now()")
        }.toString()

        return repository.update(id, body) != null
    }

    suspend fun activate(id: String, organizerId: String): Boolean {
        val existing = repository.getById(id) ?: return false
        if (existing.organizerId != organizerId) return false
        if (existing.status == "active") return true // already active

        // Close any currently active season
        val activeSeason = repository.getActiveByOrganizer(organizerId)
        if (activeSeason != null) {
            val closeBody = buildJsonObject {
                put("status", "closed")
                put("updated_at", "now()")
            }.toString()
            repository.update(activeSeason.id, closeBody)
        }

        // Activate this season
        val body = buildJsonObject {
            put("status", "active")
            put("updated_at", "now()")
        }.toString()

        return repository.update(id, body) != null
    }

    suspend fun delete(id: String, organizerId: String): Boolean {
        val existing = repository.getById(id) ?: return false
        if (existing.organizerId != organizerId) return false
        return repository.delete(id)
    }
}
