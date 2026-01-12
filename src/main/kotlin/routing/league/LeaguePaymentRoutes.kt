package routing.league

import com.incodap.security.hasAccessToOrganizer
import com.incodap.security.requireUserUid
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
import models.league.CreateLeaguePaymentRequest
import models.league.UpdateLeaguePaymentRequest
import repositories.league.SeasonRepository
import services.league.LeaguePaymentService

fun Route.leaguePaymentRoutes(
    paymentService: LeaguePaymentService,
    seasonRepository: SeasonRepository
) {
    route("/league-payments") {
        authenticate("auth-jwt") {

            // Get payment by ID
            get("{id}") {
                val userUid = call.requireUserUid() ?: return@get

                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing payment ID"))

                val payment = paymentService.getPaymentById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Payment not found"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(payment.seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to view this payment"))
                }

                call.respond(HttpStatusCode.OK, payment)
            }

            // Get payments by player ID
            get("/by-player/{playerId}") {
                val userUid = call.requireUserUid() ?: return@get

                val playerId = call.parameters["playerId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing player ID"))

                // Get player's season to verify access
                val seasonId = paymentService.getSeasonIdForPlayer(playerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))

                val season = seasonRepository.getById(seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                }

                val payments = paymentService.getPaymentsByPlayer(playerId)
                call.respond(HttpStatusCode.OK, payments)
            }

            // Get payments by season ID
            get("/by-season/{seasonId}") {
                val userUid = call.requireUserUid() ?: return@get

                val seasonId = call.parameters["seasonId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to view payments for this season"))
                }

                val payments = paymentService.getPaymentsBySeason(seasonId)
                call.respond(HttpStatusCode.OK, payments)
            }

            // Get player payment summary (includes balance due)
            get("/summary/player/{playerId}") {
                val userUid = call.requireUserUid() ?: return@get

                val playerId = call.parameters["playerId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing player ID"))

                // Get player's season to verify access
                val seasonId = paymentService.getSeasonIdForPlayer(playerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))

                val season = seasonRepository.getById(seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                }

                val summary = paymentService.getPlayerPaymentSummary(playerId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Payment data not found"))

                call.respond(HttpStatusCode.OK, summary)
            }

            // Get season payment report (all players with their payment status)
            get("/report/{seasonId}") {
                val userUid = call.requireUserUid() ?: return@get

                val seasonId = call.parameters["seasonId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to view payment report for this season"))
                }

                val report = paymentService.getSeasonPaymentReport(seasonId)
                call.respond(HttpStatusCode.OK, report)
            }

            // Get season payment statistics
            get("/stats/{seasonId}") {
                val userUid = call.requireUserUid() ?: return@get

                val seasonId = call.parameters["seasonId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(seasonId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to view statistics for this season"))
                }

                val stats = paymentService.getSeasonPaymentStats(seasonId)
                call.respond(HttpStatusCode.OK, stats)
            }

            // Create a new payment
            post {
                val userUid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<CreateLeaguePaymentRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                // Verify access to the season's organizer
                val season = seasonRepository.getById(request.seasonId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to create payments for this season"))
                }

                val result = paymentService.createPayment(request, userUid)

                result.fold(
                    onSuccess = { payment ->
                        call.respond(HttpStatusCode.Created, payment)
                    },
                    onFailure = { error ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (error.message ?: "Failed to create payment"))
                        )
                    }
                )
            }

            // Update a payment
            patch("{id}") {
                val userUid = call.requireUserUid() ?: return@patch

                val id = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing payment ID"))

                // Get payment to verify access
                val existingPayment = paymentService.getPaymentById(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Payment not found"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(existingPayment.seasonId)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@patch call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@patch call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to update this payment"))
                }

                val request = try {
                    call.receive<UpdateLeaguePaymentRequest>()
                } catch (e: Exception) {
                    return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request: ${e.localizedMessage}")
                    )
                }

                val updated = paymentService.updatePayment(id, request)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update payment"))
                }
            }

            // Delete a payment
            delete("{id}") {
                val userUid = call.requireUserUid() ?: return@delete

                val id = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing payment ID"))

                // Get payment to verify access
                val existingPayment = paymentService.getPaymentById(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Payment not found"))

                // Verify access to the season's organizer
                val season = seasonRepository.getById(existingPayment.seasonId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Season not found"))

                val organizerId = season.organizerId
                    ?: return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Season has no organizer"))

                if (!call.hasAccessToOrganizer(organizerId)) {
                    return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized to delete this payment"))
                }

                val deleted = paymentService.deletePayment(id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete payment"))
                }
            }
        }
    }
}
