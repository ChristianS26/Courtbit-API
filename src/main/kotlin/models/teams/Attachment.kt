package models.teams

@kotlinx.serialization.Serializable
data class Attachment(
    val filename: String,
    val content: String,
    val content_type: String
)
