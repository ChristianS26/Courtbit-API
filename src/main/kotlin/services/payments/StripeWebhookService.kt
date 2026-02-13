package com.incodap.services.payments

import com.incodap.repositories.payments.PaymentRepository
import com.incodap.repositories.teams.TeamRepository
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.PaymentIntent
import com.stripe.model.checkout.Session
import com.stripe.model.Event
import com.stripe.net.Webhook
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import models.payments.RpcApplyStripePaymentDto
import mu.KotlinLogging
import org.json.JSONObject
import services.email.EmailService
import repositories.tournament.TournamentRepository
import repositories.category.CategoryRepository
import repositories.discountcode.DiscountCodeRepository
import repositories.organization.OrganizationTeamRepository

val logger = KotlinLogging.logger {}

class StripeWebhookService(
    private val endpointSecret: String,
    private val paymentRepository: PaymentRepository,
    private val emailService: EmailService,
    private val tournamentRepository: TournamentRepository,
    private val categoryRepository: CategoryRepository,
    private val discountCodeRepository: DiscountCodeRepository,
    private val organizationTeamRepository: OrganizationTeamRepository
) {

    companion object {
        private val ADMIN_EMAIL: String = System.getenv("ADMIN_EMAIL") ?: "christianug26@gmail.com"
    }

    suspend fun handle(call: ApplicationCall) {
        val payload = readPayload(call) ?: return
        val signature = getSignatureHeader(call) ?: return
        val event = parseStripeEvent(payload, signature, call) ?: return

        logger.info { "📩 Evento recibido de Stripe: ${event.type}" }

        // Idempotency check — skip already-processed events
        if (paymentRepository.isWebhookProcessed(event.id)) {
            logger.info { "⏭️ Evento ${event.id} ya procesado, skip" }
            call.respond(HttpStatusCode.OK)
            return
        }

        try {
            when (event.type) {
                "checkout.session.completed" -> handleCheckoutSession(event)
                "payment_intent.succeeded"   -> handlePaymentIntentSucceeded(event, call)
                else                         -> logger.info { "ℹ️ Evento no manejado: ${event.type}" }
            }
            // Mark as processed and respond OK only if everything succeeded
            paymentRepository.markWebhookProcessed(event.id, event.type)
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            logger.error(e) { "❌ Error durante el manejo del evento ${event.type}" }
            // Return 500 so Stripe retries the webhook
            call.respond(HttpStatusCode.InternalServerError, "Processing failed")
        }
    }

    private suspend fun readPayload(call: ApplicationCall): String? = try {
        call.receiveText()
    } catch (e: Exception) {
        logger.error(e) { "❌ Error leyendo el payload del webhook" }
        call.respond(HttpStatusCode.BadRequest, "Unable to read payload")
        null
    }

    private suspend fun getSignatureHeader(call: ApplicationCall): String? {
        val sigHeader = call.request.headers["Stripe-Signature"]
        if (sigHeader == null) {
            logger.warn { "⚠️ Faltante header Stripe-Signature" }
            call.respond(HttpStatusCode.BadRequest, "Missing signature header")
        }
        return sigHeader
    }

    private suspend fun parseStripeEvent(payload: String, sigHeader: String, call: ApplicationCall) =
        try {
            Webhook.constructEvent(payload, sigHeader, endpointSecret)
        } catch (e: SignatureVerificationException) {
            logger.warn { "❌ Firma inválida del webhook: ${e.localizedMessage}" }
            call.respond(HttpStatusCode.BadRequest, "Invalid signature")
            null
        } catch (e: Exception) {
            logger.error(e) { "❌ Error general al parsear el evento" }
            call.respond(HttpStatusCode.BadRequest, "Invalid event")
            null
        }

    private fun handleCheckoutSession(event: Event) {
        val obj = event.dataObjectDeserializer.`object`
        if (obj.isPresent && obj.get() is Session) {
            val session = obj.get() as Session
            logger.info  { "✅ checkout.session.completed procesado" }
            logger.debug { "   └─ ID: ${session.id}" }
            logger.debug { "   └─ Monto total: ${session.amountTotal}" }
            logger.debug { "   └─ Email: ${session.customerDetails?.email}" }
        } else {
            logger.warn { "⚠️ Objeto Session inválido" }
        }
    }

    private suspend fun handlePaymentIntentSucceeded(event: Event, call: ApplicationCall) {
        val obj = event.dataObjectDeserializer.`object`
        val intent = if (obj.isPresent && obj.get() is PaymentIntent) {
            obj.get() as PaymentIntent
        } else {
            logger.warn { "⚠️ PaymentIntent no presente, recuperación manual iniciada" }
            retrievePaymentIntentFromJson(event, call) ?: return
        }

        logger.info  { "💳 PaymentIntent recibido" }
        logger.debug { "   └─ ID: ${intent.id}" }
        logger.debug { "   └─ Monto (cents): ${intent.amount}" }

        val tournamentId = intent.metadata["tournament_id"]
        val playerUid    = intent.metadata["player_uid"]
        val partnerUid   = intent.metadata["partner_uid"]
        val paidFor      = intent.metadata["paid_for"] ?: "1" // "1" | "2"
        val categoryId   = intent.metadata["categoryId"]
        val restriction  = intent.metadata["restriction"]
        val amount       = intent.amount
        val playerEmail  = intent.metadata["email"]
        val playerName   = intent.metadata["player_name"] ?: "Jugador"

        logger.info {
            "PI=${intent.id} | tId=$tournamentId | player=$playerUid | partner=$partnerUid | categoryId=$categoryId | amountCents=$amount"
        }

        if (tournamentId.isNullOrBlank() ||
            playerUid.isNullOrBlank() ||
            partnerUid.isNullOrBlank() ||
            categoryId.isNullOrBlank() ||
            amount == null
        ) {
            logger.warn {
                "❌ Metadata/amount incompleto: t=$tournamentId, p=$playerUid, partner=$partnerUid, c=$categoryId, amount=$amount"
            }
            return
        }

        val categoryIdInt = categoryId.toIntOrNull()
            ?: throw IllegalStateException("categoryId inválido en metadata: $categoryId")

        val ok = paymentRepository.applyStripePayment(
            RpcApplyStripePaymentDto(
                p_stripe_payment_id = intent.id,
                p_amount            = amount,
                p_tournament_id     = tournamentId,
                p_player_uid        = playerUid,
                p_partner_uid       = partnerUid,
                p_category_id       = categoryIdInt,
                p_paid_for          = paidFor,
                p_customer_id       = intent.customer,
                p_restriction       = restriction
            )
        )

        if (!ok) {
            throw IllegalStateException("RPC apply_stripe_payment falló para intent ${intent.id}")
        }

        logger.info { "✅ RPC apply_stripe_payment OK para intent ${intent.id}" }

        // Record discount code usage if one was applied
        val discountCode = intent.metadata["discount_code"]
        if (!discountCode.isNullOrBlank()) {
            try {
                val originalAmount = intent.metadata["original_amount"]?.toIntOrNull()
                val applyResult = discountCodeRepository.applyCode(
                    code = discountCode,
                    tournamentId = tournamentId,
                    playerUid = playerUid,
                    partnerUid = partnerUid,
                    categoryId = categoryId,
                    playerName = playerName,
                    restriction = restriction,
                    usedByEmail = playerEmail,
                    originalAmount = originalAmount
                )
                if (applyResult.valid && applyResult.applied == true) {
                    logger.info { "✅ Discount code '$discountCode' usage recorded for intent ${intent.id}" }
                } else {
                    logger.warn { "⚠️ Discount code '$discountCode' apply returned: valid=${applyResult.valid}, msg=${applyResult.message}" }
                }
            } catch (e: Exception) {
                logger.error(e) { "❌ Error recording discount code usage for '$discountCode': ${e.message}" }
            }
        }

        val tournamentName = tournamentRepository.getById(tournamentId)?.name
        val categoryName   = categoryRepository
            .getCategoriesByIds(listOf(categoryIdInt))
            .firstOrNull()
            ?.name

        // --- Envío de correos (flujo Stripe) ---
        val toPlayer = playerEmail?.takeIf { it.isNotBlank() }
        if (toPlayer != null) {
            emailService.sendRegistrationConfirmation(
                toEmail = toPlayer,
                playerName = playerName,
                partnerName = null,
                tournamentName = tournamentName,
                tournamentId = tournamentId,
                categoryName = categoryName,
                categoryId = categoryIdInt,
                paidFor = paidFor,
                method = "Stripe"
            )
        } else {
            logger.warn { "⚠️ No hay email en metadata para enviar confirmación al jugador" }
        }

        val ownerEmail = getOrganizerOwnerEmail(tournamentId)
        val recipients = listOfNotNull(ADMIN_EMAIL, ownerEmail).distinct()

        emailService.sendAdminNewRegistration(
            adminEmails = recipients,
            playerName = playerName,
            partnerName = null,
            playerEmail = toPlayer ?: "desconocido",
            tournamentName = tournamentName,
            tournamentId = tournamentId,
            categoryName = categoryName,
            categoryId = categoryIdInt,
            paidFor = paidFor,
            method = "Stripe"
        )
    }

    private suspend fun getOrganizerOwnerEmail(tournamentId: String): String? {
        return try {
            val tournament = tournamentRepository.getById(tournamentId) ?: return null
            val organizerId = tournament.organizerId ?: return null
            val members = organizationTeamRepository.getMembers(organizerId)
            members.firstOrNull { it.role == "owner" }?.userEmail
        } catch (e: Exception) {
            logger.warn { "No se pudo obtener email del owner para torneo $tournamentId: ${e.message}" }
            null
        }
    }

    private suspend fun retrievePaymentIntentFromJson(
        event: Event,
        call: ApplicationCall
    ): PaymentIntent? {
        return try {
            val rawJson = JSONObject(event.toJson())
            val intentId = rawJson
                .optJSONObject("data")
                ?.optJSONObject("object")
                ?.optString("id", null)

            if (!intentId.isNullOrBlank()) {
                PaymentIntent.retrieve(intentId)
            } else {
                logger.error { "❌ No se encontró el ID del PaymentIntent en el JSON del evento" }
                call.respond(HttpStatusCode.BadRequest, "Missing PaymentIntent ID")
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Error al recuperar PaymentIntent desde JSON" }
            call.respond(HttpStatusCode.BadRequest, "Failed to retrieve PaymentIntent")
            null
        }
    }
}
