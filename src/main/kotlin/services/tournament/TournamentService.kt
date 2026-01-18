package services.tournament

import com.incodap.models.tournament.UpdateTournamentRequest
import com.incodap.repositories.teams.TeamRepository
import models.category.TournamentCategoryRequest
import models.tournament.CreateTournamentRequest
import models.tournament.DeleteTournamentResult
import models.tournament.TournamentResponse
import repositories.tournament.TournamentHasPaymentsException
import repositories.tournament.TournamentRepository
import services.category.CategoryService

class TournamentService(
    private val repository: TournamentRepository,
    private val categoryService: CategoryService,
    private val teamRepository: TeamRepository,
) {

    suspend fun getAllTournaments(): List<TournamentResponse> = repository.getAll()

    suspend fun getTournamentsByOrganizer(organizerId: String): List<TournamentResponse> {
        val tournaments = repository.getByOrganizerId(organizerId)
        return tournaments.map { tournament ->
            val categories = categoryService.getCategoriesForTournament(tournament.id)
            val categoryIds = categories.map { it.id }
            tournament.copy(categoryIds = categoryIds)
        }
    }

    suspend fun getTournamentById(id: String): TournamentResponse? {
        val tournament = repository.getById(id) ?: return null
        val categories = categoryService.getCategoriesForTournament(id)
        val categoryIds = categories.map { it.id }
        return tournament.copy(categoryIds = categoryIds)
    }

    suspend fun createTournament(
        tournament: CreateTournamentRequest,
        categoryIds: List<Int>
    ): TournamentResponse? {
        println("🟡 Creando torneo con datos: $tournament")

        val created = repository.create(tournament) ?: run {
            println("🔴 No se pudo crear el torneo en Supabase")
            return null
        }
        println("🟢 Torneo creado con ID: ${created.id}")

        // Si no hay categorías, no hay nada más que hacer
        if (categoryIds.isEmpty()) {
            return created.copy(categoryIds = emptyList())
        }

        // RPC transaccional en DB (diff + deletes/insert de categorías y draws)
        val rpc = repository.setTournamentCategories(created.id, categoryIds)

        if (rpc.isFailure) {
            val cause = rpc.exceptionOrNull()?.message ?: "RPC set_tournament_categories failed"
            println("🔴 $cause")

            // 🚨 Compensación: borra el torneo para no dejar basura
            val rolledBack = runCatching { repository.delete(created.id) }.getOrDefault(false)
            if (rolledBack) {
                println("↩️ Rollback OK: torneo eliminado porque categorías no se pudieron asignar")
            } else {
                println("🧨 Rollback FALLÓ: revisar manualmente el torneo ${created.id}")
            }
            return null
        }

        // Todo OK: devuelve el torneo con categorías asignadas
        return created.copy(categoryIds = categoryIds.map { it.toString() })
    }

    suspend fun updateTournament(
        id: String,
        tournament: UpdateTournamentRequest,
        categoryIds: List<Int>
    ) {
        // 1) validación de negocio (equipos)
        val existingCategoryIds = categoryService.getCategoryIdsByTournament(id).mapNotNull { it.toIntOrNull() }
        val removed = existingCategoryIds.toSet() - categoryIds.toSet()
        if (removed.isNotEmpty()) {
            val names = categoryService.getCategoryNamesByIds(removed.toList())
            val blocked = names.filter { name -> teamRepository.hasTeamsForTournamentAndCategoryName(id, name) }
            if (blocked.isNotEmpty()) throw IllegalStateException("No se pueden eliminar categorías con equipos registrados")
        }

        // 2) patch torneo
        val updated = repository.update(id, tournament)
        if (!updated) throw IllegalStateException("No se pudo actualizar el torneo")

        // 3) RPC categorías (atómico en DB)
        val rpc = repository.setTournamentCategories(id, categoryIds)
        rpc.getOrElse { err ->
            throw IllegalStateException("No se pudieron actualizar las categorías: ${err.message}")
        }
    }

    suspend fun updateIsEnabled(id: String, isEnabled: Boolean): Boolean =
        repository.patchField(id, mapOf("is_enabled" to isEnabled), "enabled")

    suspend fun updateRegistrationOpen(id: String, registrationOpen: Boolean): Boolean =
        repository.patchField(id, mapOf("registration_open" to registrationOpen), "registration")

    // Nuevo: resultado tipado con manejo de pagos
    suspend fun deleteTournament(id: String): DeleteTournamentResult {
        println("🗑️ [TournamentService] Intentando eliminar torneo $id")

        return try {
            val deleted = repository.delete(id)

            if (deleted) {
                println("🗑️ [TournamentService] Torneo $id eliminado correctamente")
                DeleteTournamentResult.Deleted
            } else {
                println("⚠️ [TournamentService] Supabase DELETE devolvió false para torneo $id")
                DeleteTournamentResult.Error("No se pudo eliminar el torneo en Supabase.")
            }
        } catch (e: TournamentHasPaymentsException) {
            println("⛔ [TournamentService] Torneo $id tiene pagos registrados: ${e.message}")
            DeleteTournamentResult.HasPayments
        } catch (e: Exception) {
            println("🧨 [TournamentService] Error inesperado al eliminar torneo $id: ${e.stackTraceToString()}")
            DeleteTournamentResult.Error(e.message)
        }
    }

    suspend fun updateFlyerUrl(id: String, flyerUrl: String): Boolean =
        repository.updateFlyerUrl(id, flyerUrl)

    suspend fun updateClubLogoUrl(id: String, clubLogoUrl: String): Boolean =
        repository.updateClubLogoUrl(id, clubLogoUrl)
}