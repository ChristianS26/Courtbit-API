package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LeagueCategoryResponse(
    val id: String,
    @SerialName("season_id") val seasonId: String,
    val name: String,
    val level: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("player_count") val playerCount: Int? = null,
    @SerialName("waiting_list_count") val waitingListCount: Int? = null,
    @SerialName("has_calendar") val hasCalendar: Boolean? = null,
    // Maximum players for this category (16 or 20)
    @SerialName("max_players") val maxPlayers: Int = 16,
    // Playoff configuration (category override, null = use season default)
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int? = null,
    // Recommended courts for auto-scheduling (null = no preference)
    @SerialName("recommended_courts") val recommendedCourts: List<Int>? = null
)

@Serializable
data class CreateLeagueCategoryRequest(
    @SerialName("season_id") val seasonId: String,
    val name: String,
    val level: String,
    @SerialName("color_hex") val colorHex: String = "#007AFF",
    // Playoff configuration (optional, null = use season default)
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int? = null
)

@Serializable
data class UpdateLeagueCategoryRequest(
    val name: String? = null,
    val level: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    // Maximum players (16 or 20)
    @SerialName("max_players") val maxPlayers: Int? = null,
    // Playoff configuration (optional, null = keep current value, explicit value = override)
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int? = null
)

// Request to update max players and recalculate waiting list
@Serializable
data class UpdateCategoryMaxPlayersRequest(
    @SerialName("max_players") val maxPlayers: Int
)

// Request to update only playoff configuration
@Serializable
data class UpdateCategoryPlayoffConfigRequest(
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int? = null
)

// Response with effective playoff config (resolved from category or season)
@Serializable
data class CategoryPlayoffConfigResponse(
    @SerialName("category_id") val categoryId: String,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int,
    @SerialName("config_source") val configSource: String // "category" or "season"
)

// Request to update recommended courts for auto-scheduling
@Serializable
data class UpdateCategoryRecommendedCourtsRequest(
    @SerialName("recommended_courts") val recommendedCourts: List<Int>?
)
