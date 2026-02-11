package routing.payments

import com.incodap.models.payments.PaymentRequest
import com.incodap.security.email
import com.incodap.security.getOrganizerId
import com.incodap.services.payments.PaymentService
import com.incodap.services.payments.StripeConnectService
import com.incodap.services.payments.StripeWebhookService
import com.incodap.services.payments.logger
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import models.payments.AccountSessionResponse
import models.payments.ConnectAccountStatus
import models.payments.CreateConnectAccountResponse
import models.payments.PaymentsReportRequest
import repositories.organizer.OrganizerRepository

class PaymentRoutes(
    private val paymentService: PaymentService,
    private val stripeWebhookService: StripeWebhookService,
    private val stripeConnectService: StripeConnectService,
    private val organizerRepository: OrganizerRepository
) {
    fun register(route: Route) {
        // Webhook de Stripe (sin auth)
        route.post("/stripe/webhook") {
            stripeWebhookService.handle(call)
        }

        route.authenticate("auth-jwt") {

            // Checkout (actual)
            route.post("/payments/create-checkout-session") {
                try {
                    val request = call.receive<PaymentRequest>()
                    val validationErrors = request.validate()

                    if (validationErrors.isNotEmpty()) {
                        logger.warn { "❌ Invalid PaymentRequest: ${validationErrors.joinToString()}" }
                        call.respond(HttpStatusCode.BadRequest, mapOf("errors" to validationErrors))
                        return@post
                    }

                    val checkoutUrl = paymentService.createCheckoutSession(request)
                    call.respond(HttpStatusCode.OK, mapOf("url" to checkoutUrl))
                } catch (e: BadRequestException) {
                    logger.warn { "⛔ BadRequestException: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Bad request")))
                } catch (e: Exception) {
                    logger.error(e) { "❌ Error en /checkout: ${e.message}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error interno al crear sesión de pago")
                    )
                }
            }

            // 🆕 PaymentSheet (in-app): crea PaymentIntent (+ opcional Customer/EphemeralKey)
            route.post("/payments/create-intent") {
                try {
                    val request = call.receive<PaymentRequest>()
                    val validationErrors = request.validate()
                    if (validationErrors.isNotEmpty()) {
                        logger.warn { "❌ Invalid PaymentRequest: ${validationErrors.joinToString()}" }
                        call.respond(HttpStatusCode.BadRequest, mapOf("errors" to validationErrors))
                        return@post
                    }

                    val userEmail = call.email
                    val response = paymentService.createPaymentIntentForPaymentSheet(
                        request = request,
                        userEmail = userEmail
                    )
                    call.respond(HttpStatusCode.OK, response)
                } catch (e: BadRequestException) {
                    logger.warn { "⛔ BadRequestException: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Solicitud inválida")))
                } catch (e: Exception) {
                    logger.error(e) { "❌ Error en /payments/create-intent: ${e.message}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error interno al crear intent de pago")
                    )
                }
            }

            route.post("/payments/redeem-code") {
                try {
                    val req = call.receive<com.incodap.models.teams.RegisterWithCodeRequest>()
                    val usedByEmail = call.email

                    val outcome = paymentService.redeemCode(
                        code         = req.code,
                        playerUid    = req.playerUid,
                        partnerUid   = req.partnerUid,
                        categoryId   = req.categoryId,
                        tournamentId = req.tournamentId,
                        playerName   = req.playerName,
                        restriction  = req.restriction,
                        usedByEmail  = usedByEmail
                    )

                    when (outcome) {
                        PaymentService.RedeemOutcome.Created -> {
                            call.respond(HttpStatusCode.Created, mapOf("message" to "Equipo registrado exitosamente."))
                        }
                        PaymentService.RedeemOutcome.Updated -> {
                            call.respond(HttpStatusCode.OK, mapOf("message" to "Ya estabas registrado. Se actualizó tu estado de inscripción."))
                        }
                        PaymentService.RedeemOutcome.ConflictAlreadyPaid -> {
                            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Ya estás inscrito y pagado en esta categoría."))
                        }
                        PaymentService.RedeemOutcome.ConflictPartnerOther -> {
                            call.respond(HttpStatusCode.Locked, mapOf("error" to "Tu pareja ya está inscrita en esta categoría con otra persona."))
                        }
                        PaymentService.RedeemOutcome.InvalidCode -> {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Código inválido o ya utilizado."))
                        }
                    }
                } catch (e: BadRequestException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Solicitud inválida")))
                } catch (e: Exception) {
                    logger.error(e) { "❌ Error en /payments/redeem-code: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error al canjear código"))
                }
            }

            route.post("/payments/report") {
                try {
                    val req = call.receive<PaymentsReportRequest>()

                    val emailTo = (req.email?.takeIf { it.isNotBlank() }) ?: call.email
                    if (emailTo.isNullOrBlank()) throw BadRequestException("Email destino requerido")
                    if (req.tournamentId.isBlank() || req.tournamentName.isBlank()) {
                        throw BadRequestException("tournamentId y tournamentName son requeridos")
                    }

                    val ok = paymentService.sendPaymentsReport(
                        tournamentId = req.tournamentId,
                        tournamentName = req.tournamentName,
                        toEmail = emailTo
                    )

                    if (ok) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Reporte enviado a $emailTo"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo enviar el reporte"))
                    }
                } catch (e: BadRequestException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Solicitud inválida")))
                } catch (e: Exception) {
                    logger.error(e) { "❌ Error en /payments/report: ${e.message}" }
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error al generar/enviar el reporte"))
                }
            }

            // -------------------------------------------------------
            // 🔗 STRIPE CONNECT (organizer onboarding)
            // -------------------------------------------------------

            post("/connect/create-account") {
                try {
                    val organizerId = call.getOrganizerId() ?: return@post
                    val organizer = organizerRepository.getById(organizerId)
                        ?: run {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Organizer not found"))
                            return@post
                        }

                    // Check if already has a Stripe account
                    val existing = organizerRepository.getStripeAccountId(organizerId)
                    if (!existing.isNullOrBlank()) {
                        call.respond(HttpStatusCode.OK, CreateConnectAccountResponse(accountId = existing))
                        return@post
                    }

                    val email = organizer.contactEmail.takeIf { it.isNotBlank() } ?: call.email
                    val accountId = stripeConnectService.createExpressAccount(email, organizerId)
                    organizerRepository.updateStripeAccountId(organizerId, accountId)

                    call.respond(HttpStatusCode.Created, CreateConnectAccountResponse(accountId = accountId))
                } catch (e: Exception) {
                    logger.error(e) { "Error creating Stripe Connect account: ${e.message}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al crear cuenta de Stripe Connect")
                    )
                }
            }

            post("/connect/account-session") {
                try {
                    val organizerId = call.getOrganizerId() ?: return@post
                    val accountId = organizerRepository.getStripeAccountId(organizerId)

                    if (accountId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "No Stripe Connect account found. Create one first.")
                        )
                        return@post
                    }

                    val clientSecret = stripeConnectService.createAccountSession(accountId)
                    call.respond(HttpStatusCode.OK, AccountSessionResponse(clientSecret = clientSecret))
                } catch (e: Exception) {
                    logger.error(e) { "Error creating account session: ${e.message}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al crear sesión de cuenta")
                    )
                }
            }

            get("/connect/account-status") {
                try {
                    val organizerId = call.getOrganizerId() ?: return@get
                    val accountId = organizerRepository.getStripeAccountId(organizerId)

                    if (accountId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No Stripe Connect account found")
                        )
                        return@get
                    }

                    val status = stripeConnectService.getAccountStatus(accountId)
                    call.respond(HttpStatusCode.OK, status)
                } catch (e: Exception) {
                    logger.error(e) { "Error getting account status: ${e.message}" }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener estado de cuenta")
                    )
                }
            }
        }
    }
}


