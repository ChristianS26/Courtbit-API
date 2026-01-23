package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.league.BulkCreateCourtsRequest
import models.league.CopySeasonCourtsRequest
import models.league.CreateSeasonCourtRequest
import models.league.UpdateSeasonCourtRequest
import repositories.league.SeasonCourtRepository
import routing.ContentTypeException
import routing.receiveWithContentTypeCheck

fun Route.courtRoutes(
    courtRepository: SeasonCourtRepository
) {
    // Season-scoped court operations
    route("/seasons/{seasonId}/courts") {
        // GET - List all courts for a season (public - players need to see court names)
        get {
            val seasonId = call.parameters["seasonId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
            )

            val includeInactive = call.request.queryParameters["include_inactive"]?.toBoolean() ?: false
            val courts = courtRepository.getBySeasonId(seasonId, includeInactive)
            call.respond(HttpStatusCode.OK, courts)
        }

        authenticate("auth-jwt") {
            // POST - Create a single court
            post {
                call.requireOrganizer() ?: return@post

                val seasonId = call.parameters["seasonId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                )

                val request = try {
                    call.receiveWithContentTypeCheck<CreateSeasonCourtRequest>()
                } catch (e: ContentTypeException) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate name length (max 20 chars per context)
                if (request.name.length > 20) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Court name must be 20 characters or less")
                    )
                }

                val court = courtRepository.create(seasonId, request.name)
                if (court != null) {
                    call.respond(HttpStatusCode.Created, court)
                } else {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Failed to create court. Name may already exist.")
                    )
                }
            }

            // POST /bulk - Create multiple courts with default names
            post("/bulk") {
                call.requireOrganizer() ?: return@post

                val seasonId = call.parameters["seasonId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                )

                val request = try {
                    call.receiveWithContentTypeCheck<BulkCreateCourtsRequest>()
                } catch (e: ContentTypeException) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                if (request.count <= 0 || request.count > 20) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Count must be between 1 and 20")
                    )
                }

                val courts = courtRepository.bulkCreate(seasonId, request.count)
                if (courts.isNotEmpty()) {
                    call.respond(HttpStatusCode.Created, courts)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create courts")
                    )
                }
            }

            // POST /copy - Copy courts from another season
            post("/copy") {
                call.requireOrganizer() ?: return@post

                val targetSeasonId = call.parameters["seasonId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                )

                val request = try {
                    call.receiveWithContentTypeCheck<CopySeasonCourtsRequest>()
                } catch (e: ContentTypeException) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val courts = courtRepository.copyFromSeason(request.sourceSeasonId, targetSeasonId)
                if (courts.isNotEmpty()) {
                    call.respond(HttpStatusCode.Created, courts)
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "No courts found in source season or copy failed")
                    )
                }
            }
        }
    }

    // Individual court operations
    route("/courts/{id}") {
        // GET - Get single court (public)
        get {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing court ID")
            )

            val court = courtRepository.getById(id)
            if (court != null) {
                call.respond(HttpStatusCode.OK, court)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Court not found"))
            }
        }

        authenticate("auth-jwt") {
            // PATCH - Update court (name, is_active)
            patch {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing court ID")
                )

                val request = try {
                    call.receiveWithContentTypeCheck<UpdateSeasonCourtRequest>()
                } catch (e: ContentTypeException) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Validate name length if provided
                if (request.name != null && request.name.length > 20) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Court name must be 20 characters or less")
                    )
                }

                val success = courtRepository.update(id, request)
                if (success) {
                    val updated = courtRepository.getById(id)
                    if (updated != null) {
                        call.respond(HttpStatusCode.OK, updated)
                    } else {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    }
                } else {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Failed to update court. Name may already exist.")
                    )
                }
            }

            // DELETE - Soft delete court
            delete {
                call.requireOrganizer() ?: return@delete

                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing court ID")
                )

                val success = courtRepository.softDelete(id)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true, "message" to "Court deactivated"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Court not found"))
                }
            }

            // POST /reactivate - Reactivate soft-deleted court
            post("/reactivate") {
                call.requireOrganizer() ?: return@post

                val id = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing court ID")
                )

                val success = courtRepository.reactivate(id)
                if (success) {
                    val court = courtRepository.getById(id)
                    if (court != null) {
                        call.respond(HttpStatusCode.OK, court)
                    } else {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Court not found"))
                }
            }
        }
    }
}
