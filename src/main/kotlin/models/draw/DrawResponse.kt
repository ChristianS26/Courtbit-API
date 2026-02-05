package models.draw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DrawResponse(
    val id: String,
    @SerialName("tournament_id")
    val tournamentId: String,
    val category: CategoryResponse,
    @SerialName("pdf_url")
    val pdfUrl: String
)

@Serializable
data class CategoryResponse(
    val id: Int,
    val name: String
)
