package models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InheritCourtsRequest(
    @SerialName("club_id") val clubId: String
)
