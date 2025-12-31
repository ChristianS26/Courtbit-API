package com.incodap.models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeamResultStatusDto(
    @SerialName("id")
    val teamResultId: String,

    @SerialName("team_id")
    val teamId: String,

    @SerialName("position")
    val position: String,

    @SerialName("points_awarded")
    val pointsAwarded: Int,

    @SerialName("season")
    val season: String,

    @SerialName("updated_at")
    val resultUpdatedAt: String? = null
)
