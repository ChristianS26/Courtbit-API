package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import models.league.*
import services.league.PlayoffService

fun Route.playoffRoutes(
    playoffService: PlayoffService,
    json: Json
) {
    route("/league-categories/{categoryId}/playoffs") {

        // Public: Get playoff bracket with all standings and ties
        get("/bracket") {
            val categoryId = call.parameters["categoryId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
            )

            val result = playoffService.getPlayoffBracket(categoryId)

            if (result.isSuccess) {
                call.respond(HttpStatusCode.OK, result.getOrNull()!!)
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to result.exceptionOrNull()?.message)
                )
            }
        }

        // Public: Get playoff status
        get("/status") {
            val categoryId = call.parameters["categoryId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
            )

            val result = playoffService.getPlayoffStatus(categoryId)

            if (result.isSuccess) {
                val status = result.getOrNull()!!
                call.respond(
                    HttpStatusCode.OK,
                    PlayoffStatusResponse(
                        regularSeasonComplete = status.regularSeasonComplete,
                        semifinalsComplete = status.semifinalsComplete,
                        canAssignSemifinals = status.canAssignSemifinals,
                        canAssignFinal = status.canAssignFinal
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to result.exceptionOrNull()?.message)
                )
            }
        }

        authenticate("auth-jwt") {
            // Assign semifinals players
            post("/assign-semifinals") {
                call.requireOrganizer() ?: return@post

                val categoryId = call.parameters["categoryId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val result = playoffService.assignSemifinals(categoryId)

                if (result.isSuccess) {
                    val responseBody = result.getOrNull()!!

                    // Parse the JSON response from the RPC function
                    val parsedResponse = try {
                        json.decodeFromString<AssignPlayoffResponse>(responseBody)
                    } catch (e: Exception) {
                        // If parsing fails, return raw response
                        return@post call.respond(
                            HttpStatusCode.OK,
                            mapOf("success" to true, "message" to responseBody)
                        )
                    }

                    call.respond(HttpStatusCode.OK, parsedResponse)
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "Failed to assign semifinals"
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to errorMessage)
                    )
                }
            }

            // Assign final players
            post("/assign-final") {
                call.requireOrganizer() ?: return@post

                val categoryId = call.parameters["categoryId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val result = playoffService.assignFinal(categoryId)

                if (result.isSuccess) {
                    val responseBody = result.getOrNull()!!

                    // Parse the JSON response from the RPC function
                    val parsedResponse = try {
                        json.decodeFromString<AssignPlayoffResponse>(responseBody)
                    } catch (e: Exception) {
                        // If parsing fails, return raw response
                        return@post call.respond(
                            HttpStatusCode.OK,
                            mapOf("success" to true, "message" to responseBody)
                        )
                    }

                    call.respond(HttpStatusCode.OK, parsedResponse)
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: "Failed to assign final"
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to errorMessage)
                    )
                }
            }

            // Resolve playoff tie (admin only)
            post("/resolve-tie") {
                call.requireOrganizer() ?: return@post

                val request = call.receive<ResolveTieRequest>()
                val result = playoffService.resolvePlayoffTie(
                    dayGroupId = request.dayGroupId,
                    playerPositions = request.playerPositions
                )

                if (result.isSuccess) {
                    call.respond(HttpStatusCode.OK, result.getOrNull()!!)
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.exceptionOrNull()?.message)
                    )
                }
            }

            // Save/recalculate playoff standings (admin only)
            post("/save-standings") {
                call.requireOrganizer() ?: return@post

                val categoryId = call.parameters["categoryId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val result = playoffService.savePlayoffStandings(categoryId)

                if (result.isSuccess) {
                    call.respond(HttpStatusCode.OK, result.getOrNull()!!)
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.exceptionOrNull()?.message)
                    )
                }
            }
        }
    }

    // Day group specific playoff routes
    route("/playoff-groups/{dayGroupId}") {

        // Public: Get standings for a specific playoff group
        get("/standings") {
            val dayGroupId = call.parameters["dayGroupId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
            )

            val result = playoffService.calculatePlayoffStandings(dayGroupId)

            if (result.isSuccess) {
                val standings = result.getOrNull()!!
                call.respond(
                    HttpStatusCode.OK,
                    PlayoffGroupStandingsResponse(
                        dayGroupId = dayGroupId,
                        standings = standings
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to result.exceptionOrNull()?.message)
                )
            }
        }

        // Public: Detect ties in a playoff group
        get("/ties") {
            val dayGroupId = call.parameters["dayGroupId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
            )

            val result = playoffService.detectPlayoffTies(dayGroupId)

            if (result.isSuccess) {
                val ties = result.getOrNull()!!
                val actualTies = ties.filter { it.isTie }
                call.respond(
                    HttpStatusCode.OK,
                    PlayoffTiesResponse(
                        dayGroupId = dayGroupId,
                        hasTies = actualTies.isNotEmpty(),
                        ties = actualTies
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to result.exceptionOrNull()?.message)
                )
            }
        }
    }
}
