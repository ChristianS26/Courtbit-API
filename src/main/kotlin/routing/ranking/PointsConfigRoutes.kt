package routing.ranking

import com.incodap.security.requireOrganizer
import com.incodap.security.uid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import models.ranking.CreatePointsConfigRequest
import models.ranking.UpdatePointsConfigRequest
import org.koin.ktor.ext.inject
import repositories.organizer.OrganizerRepository
import services.ranking.PointsConfigService

class PointsConfigRoutes(
    private val service: PointsConfigService
) {
    fun register(route: Route) {
        route.authenticate("auth-jwt") {
            route("/points-config") {
                // GET /api/points-config — all configs for organizer
                get {
                    call.requireOrganizer() ?: return@get

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@get
                    }

                    val configs = service.getAllByOrganizer(organizer.id)
                    call.respond(configs)
                }

                // GET /api/points-config/effective?tournament_id=X&tournament_type=Y&stage=Z
                get("/effective") {
                    call.requireOrganizer() ?: return@get

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@get
                    }

                    val tournamentId = call.request.queryParameters["tournament_id"]
                    val tournamentType = call.request.queryParameters["tournament_type"] ?: "regular"
                    val stage = call.request.queryParameters["stage"] ?: "final"

                    val config = service.getEffective(organizer.id, tournamentId, tournamentType, stage)
                    if (config != null) {
                        call.respond(config)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No points config found"))
                    }
                }

                // POST /api/points-config — create config
                post {
                    call.requireOrganizer() ?: return@post

                    val organizerRepository by call.application.inject<OrganizerRepository>()
                    val organizer = organizerRepository.getByUserUid(call.uid)
                    if (organizer == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                        return@post
                    }

                    val request = call.receive<CreatePointsConfigRequest>()
                    try {
                        val created = service.create(organizer.id, request)
                        call.respond(HttpStatusCode.Created, created)
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Error creating points config"))
                        )
                    }
                }

                // PATCH /api/points-config/{id} — update config
                patch("/{id}") {
                    call.requireOrganizer() ?: return@patch

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@patch
                    }

                    val request = call.receive<UpdatePointsConfigRequest>()
                    val updated = service.update(id, request)
                    if (updated != null) {
                        call.respond(updated)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                    }
                }

                // POST /api/points-config/{id}/activate — activate config
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

                    val activated = service.activate(organizer.id, id)
                    if (activated) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Activated"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                    }
                }

                // DELETE /api/points-config/{id} — delete config
                delete("/{id}") {
                    call.requireOrganizer() ?: return@delete

                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@delete
                    }

                    val deleted = service.delete(id)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                    }
                }
            }
        }
    }
}
