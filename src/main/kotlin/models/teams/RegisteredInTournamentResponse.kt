package models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.category.CategoryResponseDto

@Serializable
data class RegisteredInTournamentResponse(
    @SerialName("registered") val registered: Boolean,
    @SerialName("items") val items: List<RegistrationItemDto> = emptyList(),
)

@Serializable
data class RegistrationTournamentDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("status") val status: String? = null, // "active" | "upcoming" | "past"
    @SerialName("type") val type: String? = null,
    @SerialName("organizer_name") val organizerName: String? = null,
)

/** Partner ahora es un TeamPlayerDto completo (snake_case). */
@Serializable
data class RegistrationItemDto(
    @SerialName("team_id") val teamId: String,
    @SerialName("tournament") val tournament: RegistrationTournamentDto,
    @SerialName("category") val category: CategoryResponseDto,
    @SerialName("partner") val partner: TeamPlayerDto?,
    @SerialName("i_am_player_a") val iAmPlayerA: Boolean,
    @SerialName("paid_by_me") val paidByMe: Boolean,
    @SerialName("paid_by_partner") val paidByPartner: Boolean,
)

