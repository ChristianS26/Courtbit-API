package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerMatchdayAvailabilityResponse(
    val id: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreatePlayerMatchdayAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

@Serializable
data class UpdatePlayerMatchdayAvailabilityRequest(
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

// Batch request for updating multiple matchdays at once
@Serializable
data class BatchPlayerMatchdayAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    val availabilities: List<MatchdayAvailabilityItem>
)

@Serializable
data class MatchdayAvailabilityItem(
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)
