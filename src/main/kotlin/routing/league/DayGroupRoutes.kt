package routing.league

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import repositories.league.DayGroupRepository

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
    }
}
