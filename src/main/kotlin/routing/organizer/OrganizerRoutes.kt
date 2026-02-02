package routing.organizer

import com.incodap.security.requireUserUid
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.organizer.CreateOrganizerRequest
import models.organizer.UpdateOrganizerRequest
import services.organizer.OrganizerService

fun Route.organizerRoutes(
    organizerService: OrganizerService
) {
    route("/organizers") {
        // Public: Get all organizers
        get {
            val organizers = organizerService.getAllOrganizers()
            call.respond(HttpStatusCode.OK, organizers)
        }

        // Public: Get organizer by ID
        get("{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Organizer ID is required")
                )
                return@get
            }

            val organizer = organizerService.getOrganizerById(id)
            if (organizer != null) {
                call.respond(HttpStatusCode.OK, organizer)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Organizer not found")
                )
            }
        }

        // Public: Get organizer statistics
        get("{id}/statistics") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Organizer ID is required")
                )
                return@get
            }

            val statistics = organizerService.getOrganizerStatistics(id)
            if (statistics != null) {
                call.respond(HttpStatusCode.OK, statistics)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Statistics not found")
                )
            }
        }

        // Protected routes (require authentication)
        authenticate("auth-jwt") {
            // Get my organizer profile
            get("/me") {
                val uid = call.requireUserUid() ?: return@get

                val organizer = organizerService.getMyOrganizer(uid)

                if (organizer != null) {
                    call.respond(HttpStatusCode.OK, organizer)
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "You don't have an organizer profile")
                    )
                }
            }

            // Check if I am an organizer
            get("/me/check") {
                val uid = call.requireUserUid() ?: return@get

                val isOrganizer = organizerService.isUserOrganizer(uid)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("isOrganizer" to isOrganizer)
                )
            }

            // Create a new organizer
            post {
                val uid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<CreateOrganizerRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body")
                    )
                    return@post
                }

                val result = organizerService.createOrganizer(request, uid)

                result.fold(
                    onSuccess = { organizer ->
                        call.respond(HttpStatusCode.Created, organizer)
                    },
                    onFailure = { error ->
                        when (error) {
                            is IllegalStateException -> {
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf("error" to (error.message ?: "User already has an organizer"))
                                )
                            }
                            is IllegalArgumentException -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (error.message ?: "Invalid request"))
                                )
                            }
                            else -> {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to create organizer")
                                )
                            }
                        }
                    }
                )
            }

            // Update my organizer
            patch("{id}") {
                val uid = call.requireUserUid() ?: return@patch

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Organizer ID is required")
                    )
                    return@patch
                }

                val request = try {
                    call.receive<UpdateOrganizerRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body")
                    )
                    return@patch
                }

                val result = organizerService.updateOrganizer(id, request, uid)

                result.fold(
                    onSuccess = {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("success" to true)
                        )
                    },
                    onFailure = { error ->
                        when (error) {
                            is IllegalArgumentException -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (error.message ?: "Invalid request"))
                                )
                            }
                            is IllegalAccessException -> {
                                call.respond(
                                    HttpStatusCode.Forbidden,
                                    mapOf("error" to (error.message ?: "Access denied"))
                                )
                            }
                            else -> {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to update organizer")
                                )
                            }
                        }
                    }
                )
            }

            // Delete my organizer
            delete("{id}") {
                val uid = call.requireUserUid() ?: return@delete

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Organizer ID is required")
                    )
                    return@delete
                }

                val result = organizerService.deleteOrganizer(id, uid)

                result.fold(
                    onSuccess = {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("success" to true)
                        )
                    },
                    onFailure = { error ->
                        when (error) {
                            is IllegalArgumentException -> {
                                call.respond(
                                    HttpStatusCode.NotFound,
                                    mapOf("error" to (error.message ?: "Organizer not found"))
                                )
                            }
                            is IllegalAccessException -> {
                                call.respond(
                                    HttpStatusCode.Forbidden,
                                    mapOf("error" to (error.message ?: "Access denied"))
                                )
                            }
                            else -> {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to delete organizer")
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}
