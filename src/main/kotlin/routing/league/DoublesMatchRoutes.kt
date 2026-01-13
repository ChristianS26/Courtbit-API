package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveOrNull
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.league.MarkForfeitRequest
import models.league.ReverseForfeitRequest
import models.league.UserScoreRequest
import models.league.UpdateMatchScoreRequest
import repositories.league.DoublesMatchRepository
import services.league.UserScoreResult
import services.league.PlayerScoreService

fun Route.doublesMatchRoutes(
    doublesMatchRepository: DoublesMatchRepository,
    playerScoreService: PlayerScoreService
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

                // Validate scores (one team must have 6, the other 0-5) OR 0-0 for reset
                val isValidScore = (request.scoreTeam1 == 6 && request.scoreTeam2 in 0..5) ||
                                   (request.scoreTeam2 == 6 && request.scoreTeam1 in 0..5) ||
                                   (request.scoreTeam1 == 0 && request.scoreTeam2 == 0) // Allow reset
                if (!isValidScore) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid score: one team must have 6 (other 0-5), or 0-0 to reset")
                    )
                }

                // Get organizer name from JWT for audit trail
                // Supabase stores user info in user_metadata claim
                val principal = call.principal<JWTPrincipal>()
                val userMetadata = principal?.payload?.getClaim("user_metadata")?.asMap()
                val organizerName = userMetadata?.get("full_name")?.toString()
                    ?: userMetadata?.get("name")?.toString()
                    ?: principal?.payload?.getClaim("email")?.asString()
                    ?: "Organizer"

                val updated = doublesMatchRepository.updateScore(id, request, organizerName)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update score")
                    )
                }
            }

            // User score submission - logs user identity for accountability
            post("{id}/user-score") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                    return@post
                }

                val matchId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing match ID")
                )

                val request = try {
                    call.receive<UserScoreRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate scores
                if (!((request.scoreTeam1 == 6 && request.scoreTeam2 in 0..5) ||
                      (request.scoreTeam2 == 6 && request.scoreTeam1 in 0..5))) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid score: one team must have 6, the other 0-5")
                    )
                }

                // Submit score with audit trail
                val result = playerScoreService.submitScore(
                    matchId = matchId,
                    userId = request.userId,
                    userName = request.userName,
                    scoreTeam1 = request.scoreTeam1,
                    scoreTeam2 = request.scoreTeam2
                )

                when (result) {
                    is UserScoreResult.Success -> {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    }
                    is UserScoreResult.Error -> {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to result.message))
                    }
                }
            }

            // Mark match as forfeit
            post("{id}/forfeit") {
                val organizerUid = call.requireOrganizer() ?: return@post

                val matchId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing match ID")
                )

                val request = try {
                    call.receive<MarkForfeitRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate: at least 1 player, max 4 players
                if (request.forfeitedPlayerIds.isEmpty() || request.forfeitedPlayerIds.size > 4) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Must specify 1-4 forfeiting players")
                    )
                }

                // Validate: one team must have 6 points (winner) or both 0 for double forfeit
                val validScore = (request.scoreTeam1 == 6 && request.scoreTeam2 == 0) ||
                                 (request.scoreTeam2 == 6 && request.scoreTeam1 == 0) ||
                                 (request.scoreTeam1 == 0 && request.scoreTeam2 == 0) // double forfeit
                if (!validScore) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid forfeit score: winner gets 6-0, or 0-0 for double forfeit")
                    )
                }

                val success = doublesMatchRepository.markForfeit(
                    matchId = matchId,
                    forfeitedPlayerIds = request.forfeitedPlayerIds,
                    scoreTeam1 = request.scoreTeam1,
                    scoreTeam2 = request.scoreTeam2,
                    recordedByUid = organizerUid
                )

                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to mark forfeit")
                    )
                }
            }

            // Reverse forfeit
            delete("{id}/forfeit") {
                call.requireOrganizer() ?: return@delete

                val matchId = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing match ID")
                )

                val request = call.receiveOrNull<ReverseForfeitRequest>()
                    ?: ReverseForfeitRequest(clearScores = true)

                val success = doublesMatchRepository.reverseForfeit(
                    matchId = matchId,
                    clearScores = request.clearScores
                )

                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to reverse forfeit")
                    )
                }
            }
        }
    }
}
