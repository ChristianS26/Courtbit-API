package models.auth

@kotlinx.serialization.Serializable
data class FieldError(
    val field: String,
    val message: String
)
