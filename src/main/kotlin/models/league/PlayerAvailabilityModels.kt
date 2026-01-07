package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayerAvailabilityResponse(
    val id: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class PlayerAvailabilityOverrideResponse(
    val id: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("override_date") val overrideDate: String,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>,
    @SerialName("is_unavailable") val isUnavailable: Boolean = false,
    val reason: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreatePlayerAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

@Serializable
data class UpdatePlayerAvailabilityRequest(
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

@Serializable
data class CreatePlayerAvailabilityOverrideRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("override_date") val overrideDate: String,
    @SerialName("available_time_slots") val availableTimeSlots: List<String> = emptyList(),
    @SerialName("is_unavailable") val isUnavailable: Boolean = false,
    val reason: String? = null
)

@Serializable
data class UpdatePlayerAvailabilityOverrideRequest(
    @SerialName("available_time_slots") val availableTimeSlots: List<String>? = null,
    @SerialName("is_unavailable") val isUnavailable: Boolean? = null,
    val reason: String? = null
)

// Batch operations for efficient updates
@Serializable
data class BatchPlayerAvailabilityRequest(
    @SerialName("player_id") val playerId: String,
    @SerialName("season_id") val seasonId: String,
    val availabilities: List<DayAvailability>
)

@Serializable
data class DayAvailability(
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("available_time_slots") val availableTimeSlots: List<String>
)

// Response for availability summary per player
@Serializable
data class PlayerAvailabilitySummary(
    @SerialName("player_id") val playerId: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("weekly_availability") val weeklyAvailability: Map<Int, List<String>>,
    val overrides: List<PlayerAvailabilityOverrideResponse>
)

// Response for checking slot availability
@Serializable
data class SlotAvailabilityResponse(
    @SerialName("time_slot") val timeSlot: String,
    val date: String,
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
