package models.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val phone: String? = null,
    val gender: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("country_iso") val countryIso: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    val birthdate: String? = null,
)
