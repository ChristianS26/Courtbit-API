package routing.league

import com.incodap.security.requireOrganizer
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
import models.league.CreateLeaguePlayerRequest
import models.league.UpdateLeaguePlayerRequest
import repositories.league.LeaguePlayerRepository

fun Route.leaguePlayerRoutes(
    leaguePlayerRepository: LeaguePlayerRepository
) {
    route("/league-players") {
        // Public: Get all players
        get {
            val players = leaguePlayerRepository.getAll()
            call.respond(HttpStatusCode.OK, players)
        }

        // Public: Get by category ID
        get("/by-category") {
            val categoryId = call.request.queryParameters["categoryId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "categoryId required"))

            val players = leaguePlayerRepository.getByCategoryId(categoryId)
            call.respond(HttpStatusCode.OK, players)
        }

        // Public: Get by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing player ID")
            )

            val player = leaguePlayerRepository.getById(id)
            if (player != null) {
                call.respond(HttpStatusCode.OK, player)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))
            }
        }

        authenticate("auth-jwt") {
            // Create player
            post {
                call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<CreateLeaguePlayerRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val created = leaguePlayerRepository.create(request)
                if (created != null) {
                    call.respond(HttpStatusCode.Created, created)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create player")
                    )
                }
            }

            // Update player
            patch("{id}") {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing player ID")
                )

                val request = call.receive<UpdateLeaguePlayerRequest>()
                val updated = leaguePlayerRepository.update(id, request)

                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update")
                    )
                }
            }

            // Delete player
            delete("{id}") {
                call.requireOrganizer() ?: return@delete

                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing player ID")
                )

                val deleted = leaguePlayerRepository.delete(id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Failed to delete"))
                }
            }
        }
    }
}
