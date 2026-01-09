package routing.league

import com.incodap.security.getOrganizerId
import com.incodap.security.hasAccessToOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.league.CreateSeasonRequest
import models.league.UpdateSeasonRequest
import repositories.league.SeasonRepository
import services.league.SeasonService

fun Route.seasonRoutes(
    seasonService: SeasonService,
    seasonRepository: SeasonRepository
) {
    route("/seasons") {
        // Public: Get all seasons
        get {
            val seasons = seasonRepository.getAll()
            call.respond(HttpStatusCode.OK, seasons)
        }

        // Public: Get by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
            )

            val season = seasonRepository.getById(id)
            if (season != null) {
                call.respond(HttpStatusCode.OK, season)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))
            }
        }

        authenticate("auth-jwt") {
            // Get my seasons (organizer-scoped - works for owners and members)
            get("/me") {
                val organizerId = call.getOrganizerId() ?: return@get

                val seasons = seasonRepository.getByOrganizerId(organizerId)
                call.respond(HttpStatusCode.OK, seasons)
            }

            // Create season
            post {
                val organizerId = call.getOrganizerId() ?: return@post

                val request = try {
                    call.receive<CreateSeasonRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val result = seasonService.createSeason(request, organizerId)

                if (result.isSuccess) {
                    call.respond(HttpStatusCode.Created, result.getOrNull()!!)
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.exceptionOrNull()?.message)
                    )
                }
            }

            // Update season
            patch("{id}") {
                val id = call.parameters["id"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                )

                // Get the season and verify access
                val season = seasonRepository.getById(id) ?: return@patch call.respond(
                    HttpStatusCode.NotFound, mapOf("error" to "Season not found")
                )

                if (!call.hasAccessToOrganizer(season.organizerId)) {
                    return@patch call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Not authorized")
                    )
                }

                val request = call.receive<UpdateSeasonRequest>()
                val updated = seasonRepository.update(id, request)

                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update")
                    )
                }
            }

            // Delete season
            delete("{id}") {
                val organizerId = call.getOrganizerId() ?: return@delete
                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                )

                val result = seasonService.deleteSeason(id, organizerId)

                if (result.isSuccess) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.exceptionOrNull()?.message)
                    )
                }
            }
        }
    }
}
