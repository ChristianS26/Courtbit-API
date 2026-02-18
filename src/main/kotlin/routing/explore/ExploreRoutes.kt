package routing.explore

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import services.explore.ExploreService

fun Route.exploreRoutes(exploreService: ExploreService) {
    route("/explore") {
        // Public with optional JWT: Get explore events feed
        authenticate("auth-jwt", optional = true) {
            get("/events") {
                val principal = call.principal<JWTPrincipal>()
                val currentUserId = principal?.getClaim("uid", String::class)
                val authHeader = call.request.headers["Authorization"]
                println("[EXPLORE] Auth header present: ${authHeader != null}, principal: ${principal != null}, userId: $currentUserId")

                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val pageSize = call.request.queryParameters["page_size"]?.toIntOrNull() ?: 20

                if (page < 1 || pageSize < 1 || pageSize > 50) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid pagination parameters")
                    )
                    return@get
                }

                val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
                val lng = call.request.queryParameters["lng"]?.toDoubleOrNull()
                val radiusKm = call.request.queryParameters["radius_km"]?.toDoubleOrNull()

                // Validate: both lat and lng must be present if one is provided
                if ((lat != null) != (lng != null)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Both 'lat' and 'lng' are required when using geo filtering")
                    )
                    return@get
                }

                // Validate radius range
                if (radiusKm != null && (radiusKm < 1 || radiusKm > 500)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "radius_km must be between 1 and 500")
                    )
                    return@get
                }

                val result = exploreService.getExploreEvents(page, pageSize, lat, lng, radiusKm, currentUserId)
                call.respond(HttpStatusCode.OK, result)
            }
        }

        // Public: Get organizers for discovery carousel
        get("/organizers") {
            val organizers = exploreService.getExploreOrganizers()
            call.respond(HttpStatusCode.OK, organizers)
        }

        // Public: Search/browse organizers with pagination
        get("/organizers/search") {
            val query = call.request.queryParameters["q"]
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["page_size"]?.toIntOrNull() ?: 20

            if (page < 1 || pageSize < 1 || pageSize > 50) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid pagination parameters")
                )
                return@get
            }

            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val lng = call.request.queryParameters["lng"]?.toDoubleOrNull()
            val sortBy = call.request.queryParameters["sort_by"] ?: "followers"
            val verifiedOnly = call.request.queryParameters["verified_only"]?.toBooleanStrictOrNull() ?: false

            if ((lat != null) != (lng != null)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Both 'lat' and 'lng' are required when using geo filtering")
                )
                return@get
            }

            val result = exploreService.searchOrganizers(query, page, pageSize, lat, lng, sortBy, verifiedOnly)
            call.respond(HttpStatusCode.OK, result)
        }
    }
}
