package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeasonResponse(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("registrations_open") val registrationsOpen: Boolean = false,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int = 2,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int = 4,
    @SerialName("organizer_id") val organizerId: String?,
    @SerialName("organizer_name") val organizerName: String?,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateSeasonRequest(
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("registrations_open") val registrationsOpen: Boolean = false,
    @SerialName("matchday_dates") val matchdayDates: List<String>? = null,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int = 2,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int = 4,
    @SerialName("organizer_id") val organizerId: String? = null
)

@Serializable
data class UpdateSeasonRequest(
    val name: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("registrations_open") val registrationsOpen: Boolean? = null,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean? = null
)
