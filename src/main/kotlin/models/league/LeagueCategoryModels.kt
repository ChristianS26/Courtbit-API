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
    @SerialName("has_calendar") val hasCalendar: Boolean? = null
)

@Serializable
data class CreateLeagueCategoryRequest(
    @SerialName("season_id") val seasonId: String,
    val name: String,
    val level: String,
    @SerialName("color_hex") val colorHex: String = "#007AFF"
)

@Serializable
data class UpdateLeagueCategoryRequest(
    val name: String? = null,
    val level: String? = null,
    @SerialName("color_hex") val colorHex: String? = null
)
