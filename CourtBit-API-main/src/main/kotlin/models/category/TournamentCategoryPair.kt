package models.category

@kotlinx.serialization.Serializable
data class TournamentCategoryPair(
    val tournament_id: String,
    val category_id: Int
)
