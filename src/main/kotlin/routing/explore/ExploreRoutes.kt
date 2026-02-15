package routing.explore

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.explore.ExploreService

fun Route.exploreRoutes(exploreService: ExploreService) {
    route("/explore") {
        // Public: Get explore events feed
        get("/events") {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["page_size"]?.toIntOrNull() ?: 20

            if (page < 1 || pageSize < 1 || pageSize > 50) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid pagination parameters")
                )
                return@get
            }

            val result = exploreService.getExploreEvents(page, pageSize)
            call.respond(HttpStatusCode.OK, result)
        }

        // Public: Get organizers for discovery carousel
        get("/organizers") {
            val organizers = exploreService.getExploreOrganizers()
            call.respond(HttpStatusCode.OK, organizers)
        }
    }
}
