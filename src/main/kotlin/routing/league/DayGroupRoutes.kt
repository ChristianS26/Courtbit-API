package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import repositories.league.DayGroupRepository
import repositories.league.RegenerateResult

fun Route.dayGroupRoutes(
    dayGroupRepository: DayGroupRepository
) {
    route("/day-groups") {
        // Get all day groups for a match day
        get("/by-match-day") {
            val matchDayId = call.request.queryParameters["matchDayId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "matchDayId required"))

            val dayGroups = dayGroupRepository.getByMatchDayId(matchDayId)
            call.respond(HttpStatusCode.OK, dayGroups)
        }

        // Get day group by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
            )

            val dayGroup = dayGroupRepository.getById(id)
            if (dayGroup != null) {
                call.respond(HttpStatusCode.OK, dayGroup)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Day group not found"))
            }
        }

        // Get rotation count for a day group
        get("{id}/rotation-count") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
            )

            val count = dayGroupRepository.getRotationCount(id)
            call.respond(HttpStatusCode.OK, mapOf("count" to count))
        }

        authenticate("auth-jwt") {
            // Regenerate rotations for a day group (organizer only)
            post("{id}/regenerate-rotations") {
                call.requireOrganizer() ?: return@post

                val id = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
                )

                when (val result = dayGroupRepository.regenerateRotations(id)) {
                    is RegenerateResult.Success -> {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Rotations created successfully"))
                    }
                    is RegenerateResult.AlreadyExists -> {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Rotations already exist"))
                    }
                    is RegenerateResult.NotEnoughPlayers -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Day group needs exactly 4 players"))
                    }
                    is RegenerateResult.Error -> {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to result.message))
                    }
                }
            }
        }
    }
}
