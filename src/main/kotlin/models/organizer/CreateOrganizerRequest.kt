package models.organizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizerRequest(
    val name: String,
    val description: String = "",
    @SerialName("contact_email") val contactEmail: String,
    @SerialName("contact_phone") val contactPhone: String = "",
    @SerialName("primary_color") val primaryColor: String = "#007AFF",
    @SerialName("secondary_color") val secondaryColor: String = "#5856D6",
    val instagram: String? = null,
    val facebook: String? = null,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
