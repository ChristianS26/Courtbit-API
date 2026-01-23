package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Season Court DTOs
 * Courts are named entities associated with a season for scheduling.
 */

@Serializable
data class SeasonCourtResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("court_number") val courtNumber: Int,
    val name: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateSeasonCourtRequest(
    @SerialName("season_id") val seasonId: String,
    val name: String
)

@Serializable
data class UpdateSeasonCourtRequest(
    val name: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class BulkCreateCourtsRequest(
    @SerialName("season_id") val seasonId: String,
    val count: Int
)

@Serializable
data class CopySeasonCourtsRequest(
    @SerialName("source_season_id") val sourceSeasonId: String,
    @SerialName("target_season_id") val targetSeasonId: String
)

// Internal DTO for creating courts with explicit court_number
@Serializable
internal data class CreateCourtInternal(
    @SerialName("season_id") val seasonId: String,
    @SerialName("court_number") val courtNumber: Int,
    val name: String,
    @SerialName("is_active") val isActive: Boolean = true
)
