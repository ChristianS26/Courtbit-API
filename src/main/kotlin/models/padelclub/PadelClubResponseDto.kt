package models.padelclub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PadelClubResponseDto(
    val id: Int,
    @SerialName("city_id")
    val cityId: Int,
    val name: String,
    val address: String? = null,
    @SerialName("cost_per_hour")
    val costPerHour: Double? = null
)

@Serializable
data class PadelClubWithCityDto(
    val id: Int,
    @SerialName("city_id")
    val cityId: Int,
    val name: String,
    val address: String? = null,
    @SerialName("cost_per_hour")
    val costPerHour: Double? = null,
    @SerialName("city_name")
    val cityName: String? = null
)
