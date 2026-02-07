package routing.ranking

import com.incodap.security.getOrganizerId
import com.incodap.security.hasAccessToOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import models.ranking.CreatePointsConfigRequest
import models.ranking.UpdatePointsConfigRequest
import services.ranking.PointsConfigService

class PointsConfigRoutes(
    private val service: PointsConfigService
) {
    fun register(route: Route) {
        route.authenticate("auth-jwt") {
            route("/points-config") {
                // GET /api/points-config — all configs for organizer
                get {
                    val organizerId = call.getOrganizerId() ?: return@get

                    val configs = service.getAllByOrganizer(organizerId)
                    call.respond(configs)
                }

                // GET /api/points-config/{id} — single config
                get("/{id}") {
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@get
                    }

                    val config = service.getById(id)
                    if (config == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                        return@get
                    }

                    if (!call.hasAccessToOrganizer(config.organizerId)) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                        return@get
                    }

                    call.respond(config)
                }

                // POST /api/points-config — create config
                post {
                    val organizerId = call.getOrganizerId() ?: return@post

                    val request = call.receive<CreatePointsConfigRequest>()
                    try {
                        val created = service.create(organizerId, request)
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
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@patch
                    }

                    val config = service.getById(id)
                    if (config == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                        return@patch
                    }

                    if (!call.hasAccessToOrganizer(config.organizerId)) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                        return@patch
                    }

                    val request = call.receive<UpdatePointsConfigRequest>()
                    val updated = service.update(id, request)
                    if (updated != null) {
                        call.respond(updated)
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update"))
                    }
                }

                // DELETE /api/points-config/{id} — delete config
                delete("/{id}") {
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        return@delete
                    }

                    val config = service.getById(id)
                    if (config == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Config not found"))
                        return@delete
                    }

                    if (!call.hasAccessToOrganizer(config.organizerId)) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                        return@delete
                    }

                    val deleted = service.delete(id)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Deleted"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete"))
                    }
                }
            }
        }
    }
}
