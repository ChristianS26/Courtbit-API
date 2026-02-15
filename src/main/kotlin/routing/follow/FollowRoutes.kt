package routing.follow

import com.incodap.security.requireUserUid
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.follow.FollowerCountResponse
import models.follow.IsFollowingResponse
import services.follow.FollowService

fun Route.followRoutes(followService: FollowService) {
    route("/organizers/{id}") {
        // Public: Get follower count
        get("/followers/count") {
            val organizerId = call.parameters["id"]
            if (organizerId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Organizer ID is required"))
                return@get
            }

            val count = followService.getFollowerCount(organizerId)
            call.respond(HttpStatusCode.OK, FollowerCountResponse(count))
        }

        // Protected routes
        authenticate("auth-jwt") {
            // Follow an organizer
            post("/follow") {
                val uid = call.requireUserUid() ?: return@post
                val organizerId = call.parameters["id"]
                if (organizerId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Organizer ID is required"))
                    return@post
                }

                val success = followService.follow(uid, organizerId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Already following or invalid organizer"))
                }
            }

            // Unfollow an organizer
            delete("/follow") {
                val uid = call.requireUserUid() ?: return@delete
                val organizerId = call.parameters["id"]
                if (organizerId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Organizer ID is required"))
                    return@delete
                }

                val success = followService.unfollow(uid, organizerId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not following this organizer"))
                }
            }

            // Check if following
            get("/is-following") {
                val uid = call.requireUserUid() ?: return@get
                val organizerId = call.parameters["id"]
                if (organizerId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Organizer ID is required"))
                    return@get
                }

                val isFollowing = followService.isFollowing(uid, organizerId)
                call.respond(HttpStatusCode.OK, IsFollowingResponse(isFollowing))
            }
        }
    }
}
