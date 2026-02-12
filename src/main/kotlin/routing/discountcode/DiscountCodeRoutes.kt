package com.incodap.routing.discountcode

import com.incodap.security.email
import com.incodap.security.getOrganizerId
import com.incodap.security.uid
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
import models.discountcode.CreateDiscountCodeRequest
import models.discountcode.UpdateDiscountCodeRequest
import models.discountcode.ValidateDiscountCodeRequest
import services.discountcode.DiscountCodeService

fun Route.discountCodeRoutes(discountCodeService: DiscountCodeService) {
    route("/discount-codes") {
        authenticate("auth-jwt") {

            // Create a discount code (organizer only)
            post("/create") {
                try {
                    val organizerId = call.getOrganizerId()
                        ?: return@post // already responded with 403

                    val userEmail = call.email.ifBlank {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Email not found in token"))
                        return@post
                    }

                    val request = call.receive<CreateDiscountCodeRequest>()
                    val errors = request.validate()
                    if (errors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("errors" to errors))
                        return@post
                    }

                    val created = discountCodeService.createDiscountCode(request, organizerId, userEmail)
                    if (created != null) {
                        call.respond(HttpStatusCode.Created, created)
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Could not create discount code. Code may already exist."))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error creating discount code")))
                }
            }

            // List discount codes for the organizer
            get {
                try {
                    val organizerId = call.getOrganizerId()
                        ?: return@get

                    val codes = discountCodeService.getDiscountCodes(organizerId)
                    call.respond(HttpStatusCode.OK, codes)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error fetching discount codes"))
                }
            }

            // Update a discount code
            patch("/{id}") {
                try {
                    val organizerId = call.getOrganizerId()
                        ?: return@patch

                    val id = call.parameters["id"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

                    val request = call.receive<UpdateDiscountCodeRequest>()
                    val updated = discountCodeService.updateDiscountCode(id, organizerId, request)
                    if (updated) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Updated"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Discount code not found or not yours"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error updating discount code"))
                }
            }

            // Delete a discount code
            delete("/{id}") {
                try {
                    val organizerId = call.getOrganizerId()
                        ?: return@delete

                    val id = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))

                    val deleted = discountCodeService.deleteDiscountCode(id, organizerId)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Deleted"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Discount code not found or not yours"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error deleting discount code"))
                }
            }

            // Validate a discount code (any authenticated user)
            post("/validate") {
                try {
                    val playerUid = call.uid
                    val request = call.receive<ValidateDiscountCodeRequest>()

                    val result = discountCodeService.validateDiscountCode(
                        code = request.code,
                        tournamentId = request.tournamentId,
                        playerUid = playerUid
                    )
                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error validating discount code")))
                }
            }
        }
    }
}
