package models.organizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateOrganizerRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("contact_email") val contactEmail: String? = null,
    @SerialName("contact_phone") val contactPhone: String? = null,
    @SerialName("primary_color") val primaryColor: String? = null,
    @SerialName("secondary_color") val secondaryColor: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    val instagram: String? = null,
    val facebook: String? = null
)
