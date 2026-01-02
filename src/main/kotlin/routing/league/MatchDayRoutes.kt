package routing.league

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import repositories.league.MatchDayRepository

fun Route.matchDayRoutes(
    matchDayRepository: MatchDayRepository
) {
    route("/match-days") {
        // Get all match days for a category
        get("/by-category") {
            val categoryId = call.request.queryParameters["categoryId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "categoryId required"))

            val matchDays = matchDayRepository.getByCategoryId(categoryId)
            call.respond(HttpStatusCode.OK, matchDays)
        }

        // Get match day by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing match day ID")
            )

            val matchDay = matchDayRepository.getById(id)
            if (matchDay != null) {
                call.respond(HttpStatusCode.OK, matchDay)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Match day not found"))
            }
        }
    }
}
