package routing.ranking

import com.incodap.security.requireOrganizer
import com.incodap.security.uid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import models.ranking.CreateRankingSeasonRequest
import models.ranking.UpdateRankingSeasonRequest
import org.koin.ktor.ext.inject
import repositories.organizer.OrganizerRepository
import services.ranking.RankingSeasonService

class RankingSeasonRoutes(
    private val service: RankingSeasonService
) {
    fun register(route: Route) {
        route.authenticate("auth-jwt") {
            route("/ranking-seasons") {
                // GET /api/ranking-seasons — all seasons for organizer
                get {
                    call.requireOrganizer() ?: return@get

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@get
                    }

                    val seasons = service.getAllByOrganizer(organizer.id)
                    call.respond(seasons)
                }

                // GET /api/ranking-seasons/active — get active season
                get("/active") {
                    call.requireOrganizer() ?: return@get

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@get
                    }

                    val season = service.getActiveByOrganizer(organizer.id)
                    if (season != null) {
                        call.respond(season)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active season found"))
                    }
                }

                // POST /api/ranking-seasons — create season (auto-closes previous active)
                post {
                    call.requireOrganizer() ?: return@post

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@post
                    }

                    val request = call.receive<CreateRankingSeasonRequest>()
                    try {
                        val created = service.create(organizer.id, request)
                        call.respond(HttpStatusCode.Created, created)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Error creating ranking season"))
                        )
                    }
                }

                // PATCH /api/ranking-seasons/{id} — update season name/dates
                patch("/{id}") {
                    call.requireOrganizer() ?: return@patch

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@patch
                    }

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@patch
                    }

                    val request = call.receive<UpdateRankingSeasonRequest>()
                    try {
                        val updated = service.update(id, organizer.id, request)
                        if (updated != null) {
                            call.respond(updated)
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))
                        }
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Error updating ranking season"))
                        )
                    }
                }

                // POST /api/ranking-seasons/{id}/close — close season
                post("/{id}/close") {
                    call.requireOrganizer() ?: return@post

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@post
                    }

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@post
                    }

                    val closed = service.close(id, organizer.id)
                    if (closed) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Season closed"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot close season"))
                    }
                }

                // POST /api/ranking-seasons/{id}/activate — reactivate season
                post("/{id}/activate") {
                    call.requireOrganizer() ?: return@post

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@post
                    }

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@post
                    }

                    val activated = service.activate(id, organizer.id)
                    if (activated) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Season activated"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot activate season"))
                    }
                }

                // DELETE /api/ranking-seasons/{id} — delete season
                delete("/{id}") {
                    call.requireOrganizer() ?: return@delete

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@delete
                    }

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@delete
                    }

                    val deleted = service.delete(id, organizer.id)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Season deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))
                    }
                }
            }
        }
    }
}
