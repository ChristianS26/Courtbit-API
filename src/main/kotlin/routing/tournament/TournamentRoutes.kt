package com.incodap.routing.tournament

import com.incodap.security.getOrganizerId
import com.incodap.security.requireOrganizer
import io.ktor.server.auth.authenticate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.tournament.CreateTournamentWithCategoriesRequest
import models.tournament.DeleteTournamentResult
import models.tournament.UpdateFlyerRequest
import models.tournament.UpdateTournamentWithCategoriesRequest
import services.category.CategoryService
import services.tournament.TournamentService

fun Route.tournamentRoutes(
    tournamentService: TournamentService,
    categoryService: CategoryService
) {
    route("/tournaments") {

        get {
            // Query parameter for filtering by organizer
            val organizerId = call.request.queryParameters["organizer_id"]

            val tournaments = if (organizerId != null) {
                tournamentService.getTournamentsByOrganizer(organizerId)
            } else {
                tournamentService.getAllTournaments()
            }

            call.respond(HttpStatusCode.OK, tournaments)
        }

        get("{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Parámetro 'id' requerido"))
                return@get
            }

            val tournament = tournamentService.getTournamentById(id)
            if (tournament != null) {
                call.respond(HttpStatusCode.OK, tournament)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Torneo no encontrado"))
            }
        }

        authenticate("auth-jwt") {
            post {
                // Get organizer ID (works for owners and members)
                val organizerId = call.getOrganizerId() ?: return@post

                val request = try {
                    call.receive<CreateTournamentWithCategoriesRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido: ${e.localizedMessage}"))
                    return@post
                }

                // Inject the organizer_id into the request
                val tournamentWithOrganizer = request.tournament.copy(organizerId = organizerId)

                val created = tournamentService.createTournament(
                    tournament = tournamentWithOrganizer,
                    categoryIds = request.categoryIds
                )

                if (created != null) {
                    call.respond(HttpStatusCode.Created, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo crear el torneo"))
                }
            }

            patch("{id}") {
                val id = call.validateOrganizerAndId() ?: return@patch

                val request = try {
                    call.receive<UpdateTournamentWithCategoriesRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido: ${e.localizedMessage}"))
                    return@patch
                }

                try {
                    tournamentService.updateTournament(
                        id = id,
                        tournament = request.tournament,
                        categoryIds = request.categoryIds
                    )
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))

                } catch (e: IllegalStateException) {
                    // 👉 devuelve el mensaje concreto del servicio/RPC
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Solicitud inválida")))
                } catch (e: Exception) {
                    // 👉 imprime el stack para depurar y responde 500 con causa
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error inesperado al actualizar el torneo: ${e.message}")
                    )
                }
            }

            patch("{id}/enabled") {
                val id = call.validateOrganizerAndId() ?: return@patch
                val payload = try { call.receive<Map<String, Boolean>>() }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido"))
                    return@patch
                }

                val isEnabled = payload["is_enabled"]
                if (isEnabled == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'is_enabled' es requerido"))
                    return@patch
                }

                val updated = tournamentService.updateIsEnabled(id, isEnabled)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo actualizar 'is_enabled'"))
                }
            }

            patch("{id}/registration-open") {
                val id = call.validateOrganizerAndId() ?: return@patch
                val payload = try { call.receive<Map<String, Boolean>>() }
                catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido"))
                    return@patch
                }

                val registrationOpen = payload["registration_open"]
                if (registrationOpen == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'registration_open' es requerido"))
                    return@patch
                }

                val updated = tournamentService.updateRegistrationOpen(id, registrationOpen)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo actualizar 'registration_open'"))
                }
            }

            delete("{id}") {
                val id = call.validateOrganizerAndId() ?: return@delete

                when (val result = tournamentService.deleteTournament(id)) {
                    DeleteTournamentResult.Deleted -> {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    }

                    DeleteTournamentResult.HasPayments -> {
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "No se puede eliminar este torneo porque tiene pagos ya registrados.")
                        )
                    }

                    is DeleteTournamentResult.Error -> {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (result.message ?: "No se pudo eliminar el torneo"))
                        )
                    }
                }
            }

            patch("{id}/flyer") {
                val id = call.validateOrganizerAndId() ?: return@patch

                val payload = try {
                    call.receive<UpdateFlyerRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido"))
                    return@patch
                }

                if (payload.flyer_url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'flyer_url' es requerido"))
                    return@patch
                }

                val updated = tournamentService.updateFlyerUrl(id, payload.flyer_url)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo actualizar el flyer"))
                }
            }

            get("/category-prices") {
                val tournamentId = call.request.queryParameters["tournamentId"]
                val tournamentType = call.request.queryParameters["tournamentType"]

                if (tournamentId.isNullOrBlank() || tournamentType.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Faltan parámetros requeridos")
                    return@get
                }

                val prices = categoryService.getCategoryPricesForTournament(
                    tournamentId = tournamentId,
                    tournamentType = tournamentType
                )

                call.respond(prices)
            }

            patch("{id}/club-logo") {
                val id = call.validateOrganizerAndId() ?: return@patch

                val payload = try {
                    call.receive<models.tournament.UpdateClubLogoRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido"))
                    return@patch
                }

                if (payload.club_logo_url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'club_logo_url' es requerido"))
                    return@patch
                }

                val updated = tournamentService.updateClubLogoUrl(id, payload.club_logo_url)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo actualizar el logo del club"))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.validateOrganizerAndId(): String? {
    if (requireOrganizer() == null) return null

    val id = parameters["id"]
    if (id.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Parámetro 'id' requerido"))
        return null
    }
    return id
}