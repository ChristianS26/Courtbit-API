package routing.league

import com.incodap.security.requireOrganizer
import com.incodap.security.requireUserUid
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
import models.league.SelfRegisterRequest
import models.league.SelfRegisterError
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
            // Get my league registrations (user's own registrations)
            get("/me/registrations") {
                val userUid = call.requireUserUid() ?: return@get

                val registrations = leaguePlayerRepository.getMyRegistrations(userUid)
                call.respond(HttpStatusCode.OK, registrations)
            }

            // Self-registration for players (user registers themselves)
            post("/register") {
                val userUid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<SelfRegisterRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        SelfRegisterError(
                            error = "Invalid request: ${e.localizedMessage}",
                            code = "INVALID_REQUEST"
                        )
                    )
                }

                val result = leaguePlayerRepository.selfRegister(userUid, request)

                result.fold(
                    onSuccess = { player ->
                        call.respond(HttpStatusCode.Created, player)
                    },
                    onFailure = { error ->
                        val (statusCode, errorCode) = when (error) {
                            is IllegalArgumentException -> HttpStatusCode.NotFound to "NOT_FOUND"
                            is IllegalStateException -> when {
                                error.message?.contains("closed") == true ->
                                    HttpStatusCode.Forbidden to "REGISTRATIONS_CLOSED"
                                error.message?.contains("already registered") == true ->
                                    HttpStatusCode.Conflict to "ALREADY_REGISTERED"
                                else -> HttpStatusCode.BadRequest to "REGISTRATION_FAILED"
                            }
                            else -> HttpStatusCode.InternalServerError to "UNKNOWN_ERROR"
                        }
                        call.respond(
                            statusCode,
                            SelfRegisterError(
                                error = error.message ?: "Registration failed",
                                code = errorCode
                            )
                        )
                    }
                )
            }

            // Create player (organizer-only)
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
