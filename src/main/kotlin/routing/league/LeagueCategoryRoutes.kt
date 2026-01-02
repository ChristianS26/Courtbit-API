package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.league.CreateLeagueCategoryRequest
import models.league.UpdateLeagueCategoryRequest
import repositories.league.LeagueCategoryRepository
import services.league.LeagueCategoryService

fun Route.leagueCategoryRoutes(
    leagueCategoryService: LeagueCategoryService,
    leagueCategoryRepository: LeagueCategoryRepository
) {
    route("/league-categories") {
        // Public: Get all categories
        get {
            val categories = leagueCategoryRepository.getAll()
            call.respond(HttpStatusCode.OK, categories)
        }

        // Public: Get by season ID
        get("/by-season") {
            val seasonId = call.request.queryParameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seasonId required"))

            val categories = leagueCategoryRepository.getBySeasonId(seasonId)
            call.respond(HttpStatusCode.OK, categories)
        }

        // Public: Get by ID with status
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
            )

            val category = leagueCategoryService.getCategoryWithStatus(id)
            if (category != null) {
                call.respond(HttpStatusCode.OK, category)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Category not found"))
            }
        }

        authenticate("auth-jwt") {
            // Create category
            post {
                call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<CreateLeagueCategoryRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val created = leagueCategoryRepository.create(request)
                if (created != null) {
                    call.respond(HttpStatusCode.Created, created)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create category")
                    )
                }
            }

            // Generate calendar for category
            post("{id}/generate-calendar") {
                call.requireOrganizer() ?: return@post

                val categoryId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val result = leagueCategoryService.generateCalendar(categoryId)

                if (result.isSuccess) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to result.getOrNull()))
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to result.exceptionOrNull()?.message)
                    )
                }
            }

            // Update category
            patch("{id}") {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val request = call.receive<UpdateLeagueCategoryRequest>()
                val updated = leagueCategoryRepository.update(id, request)

                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update")
                    )
                }
            }

            // Delete category
            delete("{id}") {
                call.requireOrganizer() ?: return@delete

                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
                )

                val deleted = leagueCategoryRepository.delete(id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Failed to delete"))
                }
            }
        }
    }
}
