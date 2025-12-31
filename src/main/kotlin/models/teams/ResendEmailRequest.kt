package models.teams

@kotlinx.serialization.Serializable
data class ResendEmailRequest(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    val attachments: List<Attachment>
)
