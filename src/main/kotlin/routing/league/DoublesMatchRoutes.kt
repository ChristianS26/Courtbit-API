package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import models.league.UpdateMatchScoreRequest
import repositories.league.DoublesMatchRepository

fun Route.doublesMatchRoutes(
    doublesMatchRepository: DoublesMatchRepository
) {
    route("/doubles-matches") {
        // Get match by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing match ID")
            )

            val match = doublesMatchRepository.getById(id)
            if (match != null) {
                call.respond(HttpStatusCode.OK, match)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Match not found"))
            }
        }

        authenticate("auth-jwt") {
            // Update match score
            patch("{id}/score") {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing match ID")
                )

                val request = try {
                    call.receive<UpdateMatchScoreRequest>()
                } catch (e: Exception) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate scores (one team must have 6, the other 0-5)
                if (!((request.scoreTeam1 == 6 && request.scoreTeam2 in 0..5) ||
                      (request.scoreTeam2 == 6 && request.scoreTeam1 in 0..5))) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid score: one team must have 6, the other 0-5")
                    )
                }

                val updated = doublesMatchRepository.updateScore(id, request)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update score")
                    )
                }
            }
        }
    }
}
