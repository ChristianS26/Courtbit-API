package routing.league

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
import repositories.league.LeaguePlayerRepository

/**
 * Routes for linking manual players to CourtBit user accounts
 */
fun Route.playerLinkRoutes(
    leaguePlayerRepository: LeaguePlayerRepository,
    userRepository: UserRepository
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

                val pendingLinks = leaguePlayerRepository.findPendingLinks(
                    email = user.email,
                    phone = user.phone
                )

                call.respond(HttpStatusCode.OK, pendingLinks)
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
                    println("❌ Player link validation failed:")
                    println("  Player email: ${player.email?.take(3)}***")
                    println("  User email: ${user.email.take(3)}***")
                    println("  Email match: $emailMatch")
                    println("  Player phone: ${player.phoneNumber?.take(3)}***")
                    println("  User phone: ${user.phone?.take(3)}***")
                    println("  Phone match: $phoneMatch")

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
        }
    }
}

/**
 * Normalize phone number for comparison by removing non-digit characters
 */
private fun normalizePhone(phone: String?): String {
    return phone?.replace(Regex("[^0-9]"), "") ?: ""
}
