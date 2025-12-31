package models.organizer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrganizerResponse(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("primary_color") val primaryColor: String = "#007AFF",
    @SerialName("secondary_color") val secondaryColor: String = "#5856D6",
    @SerialName("contact_email") val contactEmail: String,
    @SerialName("contact_phone") val contactPhone: String = "",
    val instagram: String? = null,
    val facebook: String? = null,
    @SerialName("created_by_uid") val createdByUid: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
