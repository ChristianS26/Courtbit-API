package routing.league

import com.incodap.repositories.ranking.RankingRepository
import com.incodap.repositories.teams.TeamRepository
import com.incodap.repositories.users.UserRepository
import com.incodap.security.requireUserUid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.league.LinkPlayerRequest
import models.league.PendingLinksResponse
import models.teams.LinkTournamentPlayerRequest
import models.teams.LinkTournamentPlayerResponse
import repositories.league.LeaguePlayerRepository

/**
 * Routes for linking manual players to CourtBit user accounts
 */
fun Route.playerLinkRoutes(
    leaguePlayerRepository: LeaguePlayerRepository,
    teamRepository: TeamRepository,
    userRepository: UserRepository,
    rankingRepository: RankingRepository
) {
    route("/player-links") {
        authenticate("auth-jwt") {
            // GET /api/player-links/pending - Get pending player links for current user
            get("/pending") {
                val userUid = call.requireUserUid() ?: return@get

                // Get user details to match email/phone
                val user = userRepository.findByUid(userUid)
                if (user == null) {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "User not found")
                    )
                }

                // Get league pending links
                val leagueLinks = leaguePlayerRepository.findPendingLinks(
                    email = user.email,
                    phone = user.phone
                )

                // Get tournament pending links
                val tournamentLinks = teamRepository.findPendingTournamentLinks(
                    email = user.email,
                    phone = user.phone
                )

                call.respond(HttpStatusCode.OK, PendingLinksResponse(
                    leagueLinks = leagueLinks,
                    tournamentLinks = tournamentLinks
                ))
            }

            // POST /api/player-links/link - Link a manual player to current user
            post("/link") {
                val userUid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<LinkPlayerRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Get the player to verify it matches the user's email/phone
                val player = leaguePlayerRepository.getById(request.playerId)
                if (player == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Player not found")
                    )
                }

                if (player.userUid != null) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Player is already linked to an account")
                    )
                }

                // Get user details to verify email/phone match
                val user = userRepository.findByUid(userUid)
                if (user == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "User not found")
                    )
                }

                // Security: Verify email or phone matches
                val emailMatch = player.email?.equals(user.email, ignoreCase = true) == true

                // Phone matching: Check if the last 10 digits match (handles country code variations)
                val phoneMatch = if (!player.phoneNumber.isNullOrBlank() && !user.phone.isNullOrBlank()) {
                    val playerDigits = normalizePhone(player.phoneNumber)
                    val userDigits = normalizePhone(user.phone)

                    // Compare last 10 digits (standard phone number length)
                    val playerLast10 = if (playerDigits.length > 10) playerDigits.takeLast(10) else playerDigits
                    val userLast10 = if (userDigits.length > 10) userDigits.takeLast(10) else userDigits

                    playerLast10 == userLast10
                } else {
                    false
                }

                if (!emailMatch && !phoneMatch) {
                    // Log detailed info for debugging (sanitized for privacy)

                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Player does not match your account credentials")
                    )
                }

                // Perform the link
                val result = leaguePlayerRepository.linkPlayerToUser(request.playerId, userUid)

                result.fold(
                    onSuccess = { response ->
                        call.respond(HttpStatusCode.OK, response)
                    },
                    onFailure = { error ->
                        val statusCode = when (error) {
                            is IllegalArgumentException -> HttpStatusCode.NotFound
                            is IllegalStateException -> HttpStatusCode.Conflict
                            else -> HttpStatusCode.InternalServerError
                        }
                        call.respond(
                            statusCode,
                            mapOf("error" to (error.message ?: "Failed to link player"))
                        )
                    }
                )
            }

            // POST /api/player-links/link-tournament - Link a tournament manual player to current user
            post("/link-tournament") {
                val userUid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<LinkTournamentPlayerRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate player position
                if (request.playerPosition != "a" && request.playerPosition != "b") {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "playerPosition must be 'a' or 'b'")
                    )
                }

                // Get the team to verify it has a manual player at that position
                val team = teamRepository.findTeamById(request.teamId)
                if (team == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Team not found")
                    )
                }

                // Check the position has a manual player (no uid, but has name)
                val playerUid = if (request.playerPosition == "a") team.playerAUid else team.playerBUid
                val playerName = if (request.playerPosition == "a") team.playerAName else team.playerBName
                val playerEmail = if (request.playerPosition == "a") team.playerAEmail else team.playerBEmail
                val playerPhone = if (request.playerPosition == "a") team.playerAPhone else team.playerBPhone

                if (playerUid != null) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Player is already linked to an account")
                    )
                }

                if (playerName == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "No manual player at position ${request.playerPosition}")
                    )
                }

                // Get user details to verify email/phone match
                val user = userRepository.findByUid(userUid)
                if (user == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "User not found")
                    )
                }

                // Security: Verify email or phone matches
                val emailMatch = playerEmail?.equals(user.email, ignoreCase = true) == true
                val phoneMatch = if (!playerPhone.isNullOrBlank() && !user.phone.isNullOrBlank()) {
                    val playerDigits = normalizePhone(playerPhone)
                    val userDigits = normalizePhone(user.phone)
                    val playerLast10 = if (playerDigits.length > 10) playerDigits.takeLast(10) else playerDigits
                    val userLast10 = if (userDigits.length > 10) userDigits.takeLast(10) else userDigits
                    playerLast10 == userLast10
                } else false

                if (!emailMatch && !phoneMatch) {

                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Player does not match your account credentials")
                    )
                }

                // Perform the link
                val success = teamRepository.linkTournamentPlayerToUser(
                    teamId = request.teamId,
                    playerPosition = request.playerPosition,
                    userUid = userUid
                )

                if (success) {
                    // Transfer any ranking events from this manual player to the linked user
                    try {
                        val teamMemberId = "${request.teamId}:${request.playerPosition}"
                        rankingRepository.transferRankingEventsToUser(teamMemberId, userUid)
                    } catch (e: Exception) {
                        call.application.environment.log.error(
                            "Failed to transfer ranking events for teamMember=${request.teamId}:${request.playerPosition} to user=$userUid: ${e.message}"
                        )
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        LinkTournamentPlayerResponse(
                            success = true,
                            message = "Player linked successfully"
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to link player")
                    )
                }
            }
        }
    }
}

/**
 * Normalize phone number for comparison by removing non-digit characters
 */
private fun normalizePhone(phone: String?): String {
    return phone?.replace(Regex("[^0-9]"), "") ?: ""
}
