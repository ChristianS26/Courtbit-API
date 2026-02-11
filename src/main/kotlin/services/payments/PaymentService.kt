package com.incodap.services.payments

import com.incodap.models.payments.PaymentRequest
import com.incodap.repositories.payments.PaymentRepository
import com.incodap.repositories.teams.TeamRepository
import com.incodap.repositories.users.UserRepository
import com.incodap.services.excel.ExcelService
import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.Customer
import com.stripe.model.EphemeralKey
import com.stripe.model.PaymentIntent
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.EphemeralKeyCreateParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.checkout.SessionCreateParams
import io.ktor.server.plugins.BadRequestException
import models.payments.CreateIntentResponse
import models.payments.RpcApplyCodeDto
import mu.KotlinLogging
import repositories.category.CategoryRepository
import repositories.organizer.OrganizerRepository
import repositories.tournament.TournamentRepository
import services.email.EmailService
import java.util.UUID

private val logger = KotlinLogging.logger {}

class PaymentService(
    private val teamRepository: TeamRepository,
    private val paymentRepository: PaymentRepository,
    private val excelService: ExcelService,
    private val emailService: EmailService,
    private val tournamentRepository: TournamentRepository,
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository,
    private val organizerRepository: OrganizerRepository,
) {
    companion object {
        private const val BASE_REDIRECT_URL = "https://neon-dango-f7ebd5.netlify.app"
        private val ADMIN_EMAIL: String = System.getenv("ADMIN_EMAIL") ?: "christianug26@gmail.com"
        private val STRIPE_MOBILE_SDK_API_VERSION: String =
            System.getenv("STRIPE_MOBILE_SDK_API_VERSION") ?: "2020-08-27"
        private const val PLATFORM_FEE_PERCENT = 5
    }

    init {
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY")
    }

    // -------------------------------------------------------
    // 🧾 STRIPE CHECKOUT (legacy flow, kept intact)
    // -------------------------------------------------------
    suspend fun createCheckoutSession(request: PaymentRequest): String {
        validatePaymentRequest(request)
        return createStripeSession(request)
    }

    private suspend fun validatePaymentRequest(request: PaymentRequest) {
        val playerUid = request.playerUid
        val partnerUid = request.partnerUid
        val tournamentId = request.tournamentId
        val categoryId = request.categoryId

        require(playerUid.isNotBlank()) { "playerUid es requerido" }
        require(tournamentId.isNotBlank()) { "tournamentId es requerido" }
        require(categoryId > 0) { "categoryId inválido" }

        val myTeam = teamRepository.findByPlayerAndCategory(playerUid, tournamentId, categoryId)
        if (myTeam != null) {
            if (partnerUid != myTeam.playerAUid && partnerUid != myTeam.playerBUid) {
                throw BadRequestException("Ya estás inscrito en esta categoría con otra pareja.")
            }
            val alreadyPaid = if (myTeam.playerAUid == playerUid) myTeam.playerAPaid else myTeam.playerBPaid
            if (alreadyPaid) throw BadRequestException("Ya estás inscrito y has pagado en esta categoría.")
            return
        }

        val partnerTeam = teamRepository.findByPlayerAndCategory(partnerUid, tournamentId, categoryId)
        if (partnerTeam != null) {
            val isSamePair = (partnerTeam.playerAUid == playerUid) || (partnerTeam.playerBUid == playerUid)
            if (!isSamePair) {
                throw BadRequestException("Tu pareja ya está inscrita en esta categoría con otra persona.")
            }
        }
    }

    private fun createStripeSession(request: PaymentRequest): String {
        val params = buildSessionParams(request)
        return Session.create(params).url
    }

    private fun buildSessionParams(request: PaymentRequest): SessionCreateParams =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl("$BASE_REDIRECT_URL/success.html?tournamentId=${request.tournamentId}")
            .setCancelUrl("$BASE_REDIRECT_URL/cancel.html?tournamentId=${request.tournamentId}")
            .setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS)
            .setPaymentIntentData(createPaymentIntentData(request))
            .addLineItem(createLineItem(request))
            .build()

    private fun createPaymentIntentData(request: PaymentRequest): SessionCreateParams.PaymentIntentData =
        SessionCreateParams.PaymentIntentData.builder()
            .putMetadata("tournament_id", request.tournamentId)
            .putMetadata("player_name", request.playerName)
            .putMetadata("restriction", request.restriction ?: "")
            .putMetadata("email", request.email ?: "")
            .putMetadata("partner_uid", request.partnerUid)
            .putMetadata("categoryId", request.categoryId.toString())
            .putMetadata("paid_for", request.paidFor)
            .putMetadata("player_uid", request.playerUid)
            .build()

    private fun createLineItem(request: PaymentRequest): SessionCreateParams.LineItem =
        SessionCreateParams.LineItem.builder()
            .setQuantity(1L)
            .setPriceData(
                SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(request.currency.lowercase())
                    .setUnitAmount(request.amount * 100L)
                    .setProductData(
                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                            .setName("Inscripción: ${request.playerName} - ${request.categoryId}")
                            .build()
                    )
                    .build()
            )
            .build()

    // -------------------------------------------------------
    // 💳 PAYMENT SHEET (main flow)
    // -------------------------------------------------------
    suspend fun createPaymentIntentForPaymentSheet(
        request: PaymentRequest,
        userEmail: String?
    ): CreateIntentResponse {
        validatePaymentRequest(request)

        require(request.amount > 0) { "El monto debe ser mayor a cero." }
        require(!request.currency.isNullOrBlank()) { "La moneda es requerida." }
        val normalizedCurrency = request.currency.lowercase()

        // 🔍 resolver email desde param → request → DB
        val resolvedEmail: String = run {
            val fromParam = userEmail?.trim().orEmpty()
            if (fromParam.isNotEmpty()) return@run fromParam

            val fromRequest = request.email?.trim().orEmpty()
            if (fromRequest.isNotEmpty()) return@run fromRequest

            userRepository.findByUid(request.playerUid)?.email?.trim().orEmpty()
        }

        val email = resolvedEmail
        val customer: Customer? = if (email.isNotEmpty()) {
            ensureCustomer(uid = request.playerUid, email = email)
        } else {
            null
        }

        val idem = UUID.randomUUID().toString()
        val amountInCents = request.amount * 100L

        // Resolve organizer's Stripe Connect account from tournament
        val connectedAccountId = resolveConnectedAccount(request.tournamentId)

        val params = PaymentIntentCreateParams.builder()
            .setAmount(amountInCents)
            .setCurrency(normalizedCurrency)
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .build()
            )
            .putMetadata("tournament_id", request.tournamentId)
            .putMetadata("player_name", request.playerName)
            .putMetadata("restriction", request.restriction ?: "")
            .putMetadata("email", email)
            .putMetadata("partner_uid", request.partnerUid)
            .putMetadata("categoryId", request.categoryId.toString())
            .putMetadata("paid_for", request.paidFor)
            .putMetadata("player_uid", request.playerUid)
            .apply {
                if (customer != null) {
                    setCustomer(customer.id)
                    setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION)
                }
                // Route payment to organizer's connected account
                if (connectedAccountId != null) {
                    val fee = amountInCents * PLATFORM_FEE_PERCENT / 100
                    setApplicationFeeAmount(fee)
                    setTransferData(
                        PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(connectedAccountId)
                            .build()
                    )
                    logger.info { "Payment routed to connected account $connectedAccountId (fee: $fee cents)" }
                }
            }
            .build()

        val requestOptions = RequestOptions.builder().setIdempotencyKey(idem).build()
        val intent = PaymentIntent.create(params, requestOptions)

        if (intent.clientSecret.isNullOrBlank() || !intent.clientSecret.contains("_secret_")) {
            throw IllegalStateException("Stripe no devolvió un client_secret válido.")
        }

        val ephemeralKeySecret = customer?.let {
            try {
                createEphemeralKey(it.id).secret
            } catch (e: Exception) {
                null
            }
        }

        return CreateIntentResponse(
            paymentIntentClientSecret = intent.clientSecret,
            customerId = customer?.id,
            ephemeralKey = ephemeralKeySecret
        )
    }

    // -------------------------------------------------------
    // 👥 CUSTOMER MANAGEMENT
    // -------------------------------------------------------
    @Throws(StripeException::class)
    private suspend fun ensureCustomer(uid: String, email: String): Customer {
        val existingCustomerId = userRepository.getStripeCustomerIdByUid(uid)

        if (!existingCustomerId.isNullOrBlank()) {
            return try {
                Customer.retrieve(existingCustomerId)
            } catch (ex: Exception) {
                val recreated = createCustomer(uid, email)
                userRepository.updateStripeCustomerId(uid, recreated.id)
                recreated
            }
        }

        val created = createCustomer(uid, email)
        userRepository.updateStripeCustomerId(uid, created.id)
        return created
    }

    @Throws(StripeException::class)
    private fun createCustomer(uid: String, email: String): Customer {
        val params = mapOf<String, Any>(
            "email" to email,
            "metadata" to mapOf("app_uid" to uid, "app_email" to email)
        )
        return Customer.create(params)
    }

    @Throws(StripeException::class)
    private fun createEphemeralKey(customerId: String): EphemeralKey {
        val ekParams = EphemeralKeyCreateParams.builder()
            .setStripeVersion(STRIPE_MOBILE_SDK_API_VERSION)
            .putExtraParam("customer", customerId)
            .build()

        return EphemeralKey.create(ekParams)
    }

    // -------------------------------------------------------
    // 🔗 CONNECT: resolve organizer's Stripe account
    // -------------------------------------------------------
    private suspend fun resolveConnectedAccount(tournamentId: String): String? {
        return try {
            val tournament = tournamentRepository.getById(tournamentId) ?: return null
            val organizerId = tournament.organizerId ?: return null
            val accountId = organizerRepository.getStripeAccountId(organizerId)
            if (accountId.isNullOrBlank()) null else accountId
        } catch (e: Exception) {
            logger.warn { "Could not resolve connected account for tournament $tournamentId: ${e.message}" }
            null
        }
    }

    // -------------------------------------------------------
    // 🏷️ CÓDIGOS Y REPORTES
    // -------------------------------------------------------
    enum class RedeemOutcome { Created, Updated, ConflictAlreadyPaid, ConflictPartnerOther, InvalidCode }

    suspend fun redeemCode(
        code: String,
        playerUid: String,
        partnerUid: String,
        categoryId: Int?,
        tournamentId: String,
        playerName: String,
        restriction: String?,
        usedByEmail: String?
    ): RedeemOutcome {
        if (categoryId == null) throw BadRequestException("categoryId es requerido")
        if (playerUid == partnerUid) throw BadRequestException("Los jugadores no pueden ser iguales")

        val myTeam = teamRepository.findByPlayerAndCategory(playerUid, tournamentId, categoryId)
        if (myTeam != null) {
            val samePair = partnerUid == myTeam.playerAUid || partnerUid == myTeam.playerBUid
            if (!samePair) throw BadRequestException("Ya estás inscrito en esta categoría con otra pareja.")
            val alreadyPaid = if (myTeam.playerAUid == playerUid) myTeam.playerAPaid else myTeam.playerBPaid
            if (alreadyPaid) return RedeemOutcome.ConflictAlreadyPaid
        } else {
            val partnerTeam = teamRepository.findByPlayerAndCategory(partnerUid, tournamentId, categoryId)
            if (partnerTeam != null) {
                val isSamePair = partnerTeam.playerAUid == playerUid || partnerTeam.playerBUid == playerUid
                if (!isSamePair) return RedeemOutcome.ConflictPartnerOther
            }
        }

        val ok = paymentRepository.applyRegistrationCode(
            RpcApplyCodeDto(code, tournamentId, playerUid, partnerUid, categoryId, playerName, restriction, usedByEmail)
        )

        if (!ok) return RedeemOutcome.InvalidCode

        val tournamentName = tournamentRepository.getById(tournamentId)?.name
        val categoryName = categoryRepository.getCategoriesByIds(listOf(categoryId)).firstOrNull()?.name

        val toPlayer = usedByEmail?.takeIf { it.isNotBlank() }
        if (toPlayer != null) {
            emailService.sendRegistrationConfirmation(
                toEmail = toPlayer,
                playerName = playerName,
                partnerName = null,
                tournamentName = tournamentName,
                tournamentId = tournamentId,
                categoryName = categoryName,
                categoryId = categoryId,
                paidFor = "1",
                method = "Código"
            )
        }

        emailService.sendAdminNewRegistration(
            adminEmail = ADMIN_EMAIL,
            playerName = playerName,
            partnerName = null,
            playerEmail = toPlayer ?: "desconocido",
            tournamentName = tournamentName,
            tournamentId = tournamentId,
            categoryName = categoryName,
            categoryId = categoryId,
            paidFor = "1",
            method = "Código"
        )

        return if (myTeam == null) RedeemOutcome.Created else RedeemOutcome.Updated
    }

    suspend fun sendPaymentsReport(
        tournamentId: String,
        tournamentName: String,
        toEmail: String
    ): Boolean {
        val rows = paymentRepository.getPaymentsReport(tournamentId)
        val bytes = excelService.generatePaymentsReportExcel(tournamentName, rows)
        return emailService.sendPaymentsExcelReportEmail(
            to = toEmail,
            tournamentName = tournamentName,
            attachment = bytes
        )
    }
}
