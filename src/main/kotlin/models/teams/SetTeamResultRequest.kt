package com.incodap.models.teams

import kotlinx.serialization.Serializable

@Serializable
data class SetTeamResultRequest(
    val position: String,     // "1","2","3"...
    val pointsAwarded: Int,   // puntos por jugador
    val season: String? = "2025",        // "2025"
    val adminUid: String? = null
)
