package routing.bracket

import com.incodap.security.getOrganizerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.bracket.GenerateBracketRequest
import services.bracket.BracketService

/**
 * Bracket API routes
 */
fun Route.bracketRoutes(bracketService: BracketService) {
    route("/brackets") {

        // GET /api/brackets/{categoryId}?tournament_id=xxx
        // Public - anyone can view brackets
        get("/{categoryId}") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                return@get
            }

            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val bracket = bracketService.getBracket(tournamentId, categoryId)
            if (bracket != null) {
                call.respond(HttpStatusCode.OK, bracket)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bracket not found"))
            }
        }

        authenticate("auth-jwt") {

            // POST /api/brackets/{categoryId}/generate?tournament_id=xxx
            // Requires authentication - generates bracket structure
            post("/{categoryId}/generate") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<GenerateBracketRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.localizedMessage}"))
                    return@post
                }

                // Validate request
                if (request.teamIds.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "team_ids cannot be empty"))
                    return@post
                }

                if (request.seedingMethod !in listOf("random", "manual", "ranking")) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seeding_method must be 'random', 'manual', or 'ranking'"))
                    return@post
                }

                val result = bracketService.generateBracket(
                    tournamentId = tournamentId,
                    categoryId = categoryId,
                    seedingMethod = request.seedingMethod,
                    teamIds = request.teamIds
                )

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Bracket generation failed"))) }
                )
            }

            // POST /api/brackets/{categoryId}/publish?tournament_id=xxx
            // Requires authentication - publishes bracket (locks structure)
            post("/{categoryId}/publish") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.publishBracket(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Publish failed"))) }
                )
            }

            // DELETE /api/brackets/{categoryId}?tournament_id=xxx
            // Requires authentication - deletes bracket and all matches
            delete("/{categoryId}") {
                val organizerId = call.getOrganizerId() ?: return@delete

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@delete
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@delete
                }

                // Get bracket to find its ID
                val bracket = bracketService.getBracket(tournamentId, categoryId)
                if (bracket == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bracket not found"))
                    return@delete
                }

                val deleted = bracketService.deleteBracket(bracket.bracket.id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete bracket"))
                }
            }
        }
    }
}
