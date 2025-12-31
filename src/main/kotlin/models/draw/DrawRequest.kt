package models.draw

import kotlinx.serialization.Serializable

@Serializable
data class DrawRequest(
    val tournament_id: String,
    val category_id: Int,
    val pdf_url: String
)