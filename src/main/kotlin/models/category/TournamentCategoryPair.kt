package models.category

@kotlinx.serialization.Serializable
data class TournamentCategoryPair(
    val tournament_id: String,
    val category_id: Int,
    val color: String? = null  // Hex color like "#3B82F6"
)
