package models.tournament

sealed class DeleteTournamentResult {
    object Deleted : DeleteTournamentResult()
    data class Error(val message: String? = null) : DeleteTournamentResult()
}