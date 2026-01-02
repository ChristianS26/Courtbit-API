package routing.league

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import services.league.RankingService

fun Route.leagueRankingRoutes(
    rankingService: RankingService
) {
    route("/league-rankings") {
        // Public: Get rankings for a category
        get("{categoryId}") {
            val categoryId = call.parameters["categoryId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
            )

            val rankings = rankingService.getRankings(categoryId)
            call.respond(HttpStatusCode.OK, rankings)
        }
    }
}
