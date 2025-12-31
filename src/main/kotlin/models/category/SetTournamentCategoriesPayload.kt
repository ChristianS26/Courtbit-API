package models.category// en algún paquete común de DTOs RPC
import kotlinx.serialization.Serializable

@Serializable
data class SetTournamentCategoriesPayload(
    val p_tournament_id: String,
    val p_new_categories: List<Int>
)
