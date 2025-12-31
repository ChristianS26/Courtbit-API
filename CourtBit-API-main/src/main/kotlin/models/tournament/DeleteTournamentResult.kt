package models.tournament

sealed class DeleteTournamentResult {
    object Deleted : DeleteTournamentResult()
    object HasPayments : DeleteTournamentResult()
    data class Error(val message: String? = null) : DeleteTournamentResult()
}