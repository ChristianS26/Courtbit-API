package routing.league

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import repositories.league.MatchDayRepository
import services.league.MatchDayService

/**
 * Optimized match day routes that fetch all nested data in minimal requests
 */
fun Route.matchDayOptimizedRoutes(
    matchDayService: MatchDayService
) {
    route("/match-days") {
        // Get complete match day with all nested data (day groups, rotations, matches, players)
        // This replaces 13+ separate requests with 1 optimized request
        get("{id}/complete") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing match day ID")
            )

            val completeMatchDay = matchDayService.getCompleteMatchDay(id)
            if (completeMatchDay != null) {
                call.respond(HttpStatusCode.OK, completeMatchDay)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Match day not found"))
            }
        }
    }
}
