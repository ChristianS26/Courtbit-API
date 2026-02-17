package repositories.tournament

import com.incodap.models.tournament.UpdateTournamentRequest
import models.tournament.CreateTournamentRequest
import models.tournament.TournamentResponse

interface TournamentRepository {
    suspend fun getAll(): List<TournamentResponse>
    suspend fun getByOrganizerId(organizerId: String): List<TournamentResponse>
    suspend fun getById(id: String): TournamentResponse?
    suspend fun create(request: CreateTournamentRequest): TournamentResponse?
    suspend fun update(id: String, request: UpdateTournamentRequest): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun updateFlyerUrl(id: String, flyerUrl: String, flyerPosition: String? = null): Boolean
    suspend fun patchField(id: String, fields: Map<String, Any>, patchType: String): Boolean
    suspend fun updateClubLogoUrl(id: String, logoUrl: String): Boolean
    suspend fun setTournamentCategories(tournamentId: String, categoryIds: List<Int>): Result<Unit>
    suspend fun setCategoryPrices(tournamentId: String, categoryPrices: Map<Int, Int>): Boolean
    suspend fun setCategoryColors(tournamentId: String, categoryColors: Map<Int, String>): Boolean
    suspend fun getSchedulingConfig(tournamentId: String): models.tournament.SchedulingConfigResponse?
    suspend fun saveSchedulingConfig(tournamentId: String, config: models.tournament.SchedulingConfigRequest): Boolean
    suspend fun getRestrictionConfig(tournamentId: String): models.tournament.RestrictionConfigResponse?
    suspend fun saveRestrictionConfig(tournamentId: String, config: models.tournament.RestrictionConfigRequest): Boolean
}
