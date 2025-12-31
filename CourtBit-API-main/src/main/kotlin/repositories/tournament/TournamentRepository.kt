package repositories.tournament

import com.incodap.models.tournament.UpdateTournamentRequest
import models.tournament.CreateTournamentRequest
import models.tournament.TournamentResponse

interface TournamentRepository {
    suspend fun getAll(): List<TournamentResponse>
    suspend fun getById(id: String): TournamentResponse?
    suspend fun create(request: CreateTournamentRequest): TournamentResponse?
    suspend fun update(id: String, request: UpdateTournamentRequest): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun updateFlyerUrl(id: String, flyerUrl: String): Boolean
    suspend fun patchField(id: String, fields: Map<String, Any>, patchType: String): Boolean
    suspend fun updateClubLogoUrl(id: String, logoUrl: String): Boolean
    suspend fun setTournamentCategories(tournamentId: String, categoryIds: List<Int>): Result<Unit>
}
