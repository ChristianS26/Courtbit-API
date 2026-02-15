package routing.explore

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.explore.ExploreService
import services.follow.FollowService

fun Route.exploreRoutes(exploreService: ExploreService, followService: FollowService) {
    route("/explore") {
        // Events feed with optional JWT for following filter
        authenticate("auth-jwt", optional = true) {
            get("/events") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val pageSize = call.request.queryParameters["page_size"]?.toIntOrNull() ?: 20
                val following = call.request.queryParameters["following"]?.toBooleanStrictOrNull() ?: false

                if (page < 1 || pageSize < 1 || pageSize > 50) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid pagination parameters")
                    )
                    return@get
                }

                val followedOrgIds = if (following) {
                    val userId = call.principal<JWTPrincipal>()?.getClaim("uid", String::class)
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Authentication required to filter by followed organizers")
                        )
                    followService.getFollowedOrganizerIds(userId)
                } else null

                val result = exploreService.getExploreEvents(page, pageSize, followedOrgIds)
                call.respond(HttpStatusCode.OK, result)
            }
        }

        // Organizers: carousel (no page) or paginated (with page)
        get("/organizers") {
            val page = call.request.queryParameters["page"]?.toIntOrNull()
            val pageSize = call.request.queryParameters["page_size"]?.toIntOrNull() ?: 20
            val search = call.request.queryParameters["search"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()

            if (page != null) {
                if (page < 1 || pageSize < 1 || pageSize > 50) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid pagination parameters")
                    )
                    return@get
                }
                val result = exploreService.getExploreOrganizersPaginated(page, pageSize, search)
                call.respond(HttpStatusCode.OK, result)
            } else {
                val organizers = exploreService.getExploreOrganizers(limit ?: 10)
                call.respond(HttpStatusCode.OK, organizers)
            }
        }
    }
}
