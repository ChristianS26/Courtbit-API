package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerAvailabilityResponse(
    val id: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreatePlayerAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

@Serializable
data class UpdatePlayerAvailabilityRequest(
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

// Batch operations for efficient updates
@Serializable
data class BatchPlayerAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    val availabilities: List<MatchdayAvailability>
)

@Serializable
data class MatchdayAvailability(
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

// Response for availability summary per player
@Serializable
data class PlayerAvailabilitySummary(
    @SerialName("player_id") val playerId: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("matchday_availability") val matchdayAvailability: Map<Int, List<String>>
)

// Response for checking slot availability
@Serializable
data class SlotAvailabilityResponse(
    @SerialName("time_slot") val timeSlot: String,
    @SerialName("matchday_number") val matchdayNumber: Int,
    @SerialName("available_players") val availablePlayers: List<AvailablePlayer>,
    @SerialName("unavailable_players") val unavailablePlayers: List<UnavailablePlayer>
)

@Serializable
data class AvailablePlayer(
    val id: String,
    val name: String
)

@Serializable
data class UnavailablePlayer(
    val id: String,
    val name: String,
    val reason: String? = null
)
