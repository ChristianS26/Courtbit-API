package com.incodap.models.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.teams.ScheduleRestriction

@Serializable
data class RegisterWithCodeRequest(
    val code: String,
    val playerUid: String,
    val partnerUid: String,
    val categoryId: Int? = null,
    val tournamentId: String,
    val email: String = "",
    val restriction: String? = "",
    val playerName: String = "",
    @SerialName("schedule_restriction") val scheduleRestriction: ScheduleRestriction? = null,
)
