package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayoffStatusResponse(
    @SerialName("regular_season_complete") val regularSeasonComplete: Boolean,
    @SerialName("semifinals_complete") val semifinalsComplete: Boolean,
    @SerialName("can_assign_semifinals") val canAssignSemifinals: Boolean,
    @SerialName("can_assign_final") val canAssignFinal: Boolean
)

@Serializable
data class AssignPlayoffResponse(
    val success: Boolean,
    val message: String? = null,
    @SerialName("players_assigned") val playersAssigned: Int? = null,
    val groups: Int? = null,
    @SerialName("direct_qualifiers") val directQualifiers: Int? = null,
    @SerialName("semifinals_winners") val semifinalsWinners: Int? = null,
    @SerialName("total_in_final") val totalInFinal: Int? = null,
    @SerialName("final_groups") val finalGroups: Int? = null
)
