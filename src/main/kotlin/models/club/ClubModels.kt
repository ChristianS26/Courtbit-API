package com.incodap.models.club

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Club(
    val id: String,
    @SerialName("organizer_id") val organizerId: String,
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class ClubCourt(
    val id: String,
    @SerialName("club_id") val clubId: String,
    @SerialName("court_number") val courtNumber: Int,
    val name: String,
    @SerialName("available_from") val availableFrom: String = "09:00:00",
    @SerialName("available_to") val availableTo: String = "22:00:00",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateClubRequest(
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("logo_url") val logoUrl: String? = null
)

@Serializable
data class UpdateClubRequest(
    val name: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("logo_url") val logoUrl: String? = null
)

@Serializable
data class CreateClubCourtRequest(
    @SerialName("court_number") val courtNumber: Int,
    val name: String,
    @SerialName("available_from") val availableFrom: String? = null,
    @SerialName("available_to") val availableTo: String? = null
)

@Serializable
data class UpdateClubCourtRequest(
    @SerialName("court_number") val courtNumber: Int? = null,
    val name: String? = null,
    @SerialName("available_from") val availableFrom: String? = null,
    @SerialName("available_to") val availableTo: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

@Serializable
data class ClubWithCourtsResponse(
    val id: String,
    @SerialName("organizer_id") val organizerId: String,
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val courts: List<ClubCourt> = emptyList()
)
