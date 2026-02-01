package com.incodap.models.tournament

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateTournamentRequest(
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val type: String,
    @SerialName("max_points") val maxPoints: String? = null,
    @SerialName("flyer_url") val flyerUrl: String? = null,
    @SerialName("club_logo_url") val clubLogoUrl: String? = null,
    @SerialName("city_id") val cityId: Int? = null,
    @SerialName("padel_club_id") val padelClubId: Int? = null,
)