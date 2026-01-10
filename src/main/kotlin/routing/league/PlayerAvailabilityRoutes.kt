package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import models.league.*
import repositories.league.PlayerAvailabilityRepository

fun Route.playerAvailabilityRoutes(
    repository: PlayerAvailabilityRepository
) {
    route("/player-availability") {

        // ==================== Public Read Endpoints ====================

        // Get matchday availability for a player in a season
        get("/player/{playerId}") {
            val playerId = call.parameters["playerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "playerId required"))
            val seasonId = call.request.queryParameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))

            val availability = repository.getByPlayerId(playerId, seasonId)
            call.respond(HttpStatusCode.OK, availability)
        }

        // Get all availability for a season
        get("/season/{seasonId}") {
            val seasonId = call.parameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))

            val availability = repository.getBySeasonId(seasonId)
            call.respond(HttpStatusCode.OK, availability)
        }

        // Get availability for a specific matchday
        get("/season/{seasonId}/matchday/{matchdayNumber}") {
            val seasonId = call.parameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))
            val matchdayNumber = call.parameters["matchdayNumber"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "matchdayNumber required"))

            val availability = repository.getBySeasonAndMatchday(seasonId, matchdayNumber)
            call.respond(HttpStatusCode.OK, availability)
        }

        // Get full summary for a player
        get("/summary/{playerId}") {
            val playerId = call.parameters["playerId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "playerId required"))
            val seasonId = call.request.queryParameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))

            val summary = repository.getPlayerAvailabilitySummary(playerId, seasonId)
            if (summary != null) {
                call.respond(HttpStatusCode.OK, summary)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))
            }
        }

        // Check which players are available for a specific slot
        get("/check-slot") {
            val categoryId = call.request.queryParameters["categoryId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "categoryId required"))
            val seasonId = call.request.queryParameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))
            val matchdayNumber = call.request.queryParameters["matchdayNumber"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "matchdayNumber required (1-7)"))
            val timeSlot = call.request.queryParameters["timeSlot"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "timeSlot required (HH:mm:ss)"))

            val result = repository.getAvailabilityForSlot(categoryId, seasonId, matchdayNumber, timeSlot)
            call.respond(HttpStatusCode.OK, result)
        }

        // ==================== Authenticated Write Endpoints ====================

        authenticate("auth-jwt") {
            // Create single availability record
            post {
                call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<CreatePlayerAvailabilityRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val created = repository.create(request)
                if (created != null) {
                    call.respond(HttpStatusCode.Created, created)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create availability")
                    )
                }
            }

            // Batch upsert availability (replace all matchdays for a player in a season)
            post("/batch") {
                call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<BatchPlayerAvailabilityRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val success = repository.upsertBatch(request)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update availability")
                    )
                }
            }

            // Update single availability record
            patch("/{id}") {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))

                val request = try {
                    call.receive<UpdatePlayerAvailabilityRequest>()
                } catch (e: Exception) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val success = repository.update(id, request)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update availability")
                    )
                }
            }

            // Delete single availability record
            delete("/{id}") {
                call.requireOrganizer() ?: return@delete

                val id = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id required"))

                val success = repository.delete(id)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Failed to delete availability"))
                }
            }
        }
    }
}
