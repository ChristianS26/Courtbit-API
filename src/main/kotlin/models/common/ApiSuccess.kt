package models.common

import kotlinx.serialization.Serializable

@Serializable
data class ApiSuccess(val success: Boolean = true)

@Serializable
data class ApiError(
    val success: Boolean = false,
    val error: String,
    val exception: String? = null
)
