package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import models.league.CreateAdjustmentRequest
import models.league.UpdateAdjustmentRequest
import repositories.league.AdjustmentRepository

fun Route.adjustmentRoutes(
    adjustmentRepository: AdjustmentRepository
) {
    route("/adjustments") {
        // Public: Get adjustments by category
        get("/by-category") {
            val categoryId = call.request.queryParameters["categoryId"]
            if (categoryId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    buildJsonObject { put("error", "categoryId query parameter is required") }
                )
                return@get
            }

            val adjustments = adjustmentRepository.getByCategory(categoryId)
            call.respond(HttpStatusCode.OK, adjustments)
        }

        // Public: Get adjustment by ID
        get("{id}") {
            val id = call.parameters["id"]
            if (id.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    buildJsonObject { put("error", "Adjustment ID is required") }
                )
                return@get
            }

            val adjustment = adjustmentRepository.getById(id)
            if (adjustment != null) {
                call.respond(HttpStatusCode.OK, adjustment)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    buildJsonObject { put("error", "Adjustment not found") }
                )
            }
        }

        // Protected routes (require organizer)
        authenticate("auth-jwt") {
            // Create adjustment
            post {
                val uid = call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<CreateAdjustmentRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Invalid request body") }
                    )
                    return@post
                }

                // Validate value range (-50 to +50)
                if (request.value < -50 || request.value > 50) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Adjustment value must be between -50 and +50") }
                    )
                    return@post
                }

                // Validate reason is not empty
                if (request.reason.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Reason is required") }
                    )
                    return@post
                }

                val created = adjustmentRepository.create(request, uid)
                if (created != null) {
                    call.respond(HttpStatusCode.Created, created)
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        buildJsonObject { put("error", "Failed to create adjustment") }
                    )
                }
            }

            // Update adjustment
            patch("{id}") {
                call.requireOrganizer() ?: return@patch

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Adjustment ID is required") }
                    )
                    return@patch
                }

                val request = try {
                    call.receive<UpdateAdjustmentRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Invalid request body") }
                    )
                    return@patch
                }

                // Validate value range if provided
                if (request.value != null && (request.value < -50 || request.value > 50)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Adjustment value must be between -50 and +50") }
                    )
                    return@patch
                }

                // Check if adjustment exists
                val existing = adjustmentRepository.getById(id)
                if (existing == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        buildJsonObject { put("error", "Adjustment not found") }
                    )
                    return@patch
                }

                val updated = adjustmentRepository.update(id, request)
                if (updated) {
                    call.respond(
                        HttpStatusCode.OK,
                        buildJsonObject { put("success", true) }
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        buildJsonObject { put("error", "Failed to update adjustment") }
                    )
                }
            }

            // Delete adjustment
            delete("{id}") {
                call.requireOrganizer() ?: return@delete

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "Adjustment ID is required") }
                    )
                    return@delete
                }

                // Check if adjustment exists
                val existing = adjustmentRepository.getById(id)
                if (existing == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        buildJsonObject { put("error", "Adjustment not found") }
                    )
                    return@delete
                }

                val deleted = adjustmentRepository.delete(id)
                if (deleted) {
                    call.respond(
                        HttpStatusCode.OK,
                        buildJsonObject { put("success", true) }
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        buildJsonObject { put("error", "Failed to delete adjustment") }
                    )
                }
            }
        }
    }
}
