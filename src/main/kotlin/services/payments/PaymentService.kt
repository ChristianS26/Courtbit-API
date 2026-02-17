package com.incodap.services.payments

import com.incodap.models.payments.PaymentRequest
import com.incodap.repositories.payments.PaymentRepository
import com.incodap.repositories.teams.TeamRepository
import com.incodap.repositories.users.UserRepository
import com.incodap.services.excel.ExcelService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import models.teams.ScheduleRestriction
import repositories.discountcode.DiscountCodeRepository
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
import repositories.registrationcode.RegistrationCodeRepository
import repositories.organization.OrganizationTeamRepository
import repositories.tournament.TournamentRepository
import services.email.EmailService
import java.util.UUID

private val paymentLogger = KotlinLogging.logger {}

class PaymentService(
    private val teamRepository: TeamRepository,
    private val paymentRepository: PaymentRepository,
    private val excelService: ExcelService,
    private val emailService: EmailService,
    private val tournamentRepository: TournamentRepository,
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository,
    private val organizerRepository: OrganizerRepository,
    private val registrationCodeRepository: RegistrationCodeRepository,
    private val discountCodeRepository: DiscountCodeRepository,
    private val stripeConnectService: StripeConnectService,
    private val organizationTeamRepository: OrganizationTeamRepository,
) {
    companion object {
        private const val BASE_REDIRECT_URL = "https://neon-dango-f7ebd5.netlify.app"
        private val ADMIN_EMAIL: String = System.getenv("ADMIN_EMAIL") ?: "christianug26@gmail.com"
        private val STRIPE_MOBILE_SDK_API_VERSION: String =
            System.getenv("STRIPE_MOBILE_SDK_API_VERSION") ?: "2020-08-27"
        private const val PLATFORM_FEE_PERCENT = 0
    }

    init {
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY")
    }

    // -------------------------------------------------------
    // 🧾 STRIPE CHECKOUT (legacy flow)
    // -------------------------------------------------------
    suspend fun createCheckoutSession(request: PaymentRequest): String {
        validatePaymentRequest(request)

        // Server-side price lookup — never trust the client amount
        val categoryPrice = categoryRepository.getCategoryPrice(request.tournamentId, request.categoryId)
            ?: throw BadRequestException("No se encontró precio para la categoría ${request.categoryId}")
        require(categoryPrice > 0) { "El precio de la categoría debe ser mayor a cero." }
        val paidFor = (request.paidFor.toIntOrNull() ?: 1).coerceIn(1, 2)
        val serverAmount = categoryPrice * paidFor

        return createStripeSession(request, serverAmount)
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

    private fun createStripeSession(request: PaymentRequest, serverAmount: Long): String {
        val params = buildSessionParams(request, serverAmount)
        return Session.create(params).url
    }

    private fun buildSessionParams(request: PaymentRequest, serverAmount: Long): SessionCreateParams =
        SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl("$BASE_REDIRECT_URL/success.html?tournamentId=${request.tournamentId}")
            .setCancelUrl("$BASE_REDIRECT_URL/cancel.html?tournamentId=${request.tournamentId}")
            .setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS)
            .setPaymentIntentData(createPaymentIntentData(request))
            .addLineItem(createLineItem(request, serverAmount))
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
            .apply {
                request.scheduleRestriction?.let {
                    putMetadata("schedule_restriction", Json.encodeToString(it))
                }
            }
            .build()

    private fun createLineItem(request: PaymentRequest, serverAmount: Long): SessionCreateParams.LineItem =
        SessionCreateParams.LineItem.builder()
            .setQuantity(1L)
            .setPriceData(
                SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(request.currency.lowercase())
                    .setUnitAmount(serverAmount)
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

        require(!request.currency.isNullOrBlank()) { "La moneda es requerida." }
        val normalizedCurrency = request.currency.lowercase()

        // Server-side price lookup — never trust the client amount
        val categoryPrice = categoryRepository.getCategoryPrice(request.tournamentId, request.categoryId)
            ?: throw BadRequestException("No se encontró precio para la categoría ${request.categoryId}")
        require(categoryPrice > 0) { "El precio de la categoría debe ser mayor a cero." }

        val paidFor = (request.paidFor.toIntOrNull() ?: 1).coerceIn(1, 2)
        val amountInCents = categoryPrice * paidFor

        paymentLogger.info { "Price from DB: categoryPrice=$categoryPrice, paidFor=$paidFor, amountInCents=$amountInCents" }

        // 🏷️ Discount code handling
        if (!request.discountCode.isNullOrBlank()) {
            val validation = discountCodeRepository.validateCode(
                request.discountCode, request.tournamentId, request.playerUid
            )

            if (!validation.valid) {
                throw BadRequestException(validation.message ?: "Invalid discount code")
            }

            // Calculate amounts server-side using category price + discount info
            val discountAmount: Long = when (validation.discountType) {
                "percentage" -> amountInCents * (validation.discountValue ?: 0) / 100
                "fixed_amount" -> minOf((validation.discountValue ?: 0).toLong(), amountInCents)
                else -> 0L
            }
            val finalAmount: Long = maxOf(0L, amountInCents - discountAmount)

            if (finalAmount <= 0L) {
                // 100% discount — apply code and register team for free via RPC
                val applyResult = discountCodeRepository.applyCode(
                    code = request.discountCode,
                    tournamentId = request.tournamentId,
                    playerUid = request.playerUid,
                    partnerUid = request.partnerUid,
                    categoryId = request.categoryId.toString(),
                    playerName = request.playerName,
                    restriction = request.restriction,
                    usedByEmail = request.email,
                    originalAmount = amountInCents.toInt()
                )

                if (!applyResult.valid || applyResult.applied != true) {
                    throw BadRequestException(applyResult.message ?: "Failed to apply discount code")
                }

                // Set schedule_restriction on the newly created team
                if (request.scheduleRestriction != null) {
                    try {
                        val team = teamRepository.findByPlayerAndCategory(
                            request.playerUid, request.tournamentId, request.categoryId
                        )
                        if (team != null) {
                            teamRepository.updateTeamScheduleRestriction(
                                team.id, Json.encodeToString(request.scheduleRestriction)
                            )
                        }
                    } catch (e: Exception) {
                        paymentLogger.warn { "Failed to set schedule_restriction after 100% discount: ${e.message}" }
                    }
                }

                return CreateIntentResponse(
                    paymentIntentClientSecret = null,
                    isFreeRegistration = true,
                    originalAmount = amountInCents,
                    discountApplied = discountAmount,
                    finalAmount = 0,
                    discountCode = request.discountCode
                )
            }

            // Partial discount — continue with reduced amount
            paymentLogger.info { "Discount applied: original=$amountInCents, discount=$discountAmount, final=$finalAmount" }
            return createPaymentIntentWithDiscount(
                request = request,
                userEmail = userEmail,
                originalAmount = amountInCents,
                finalAmount = finalAmount,
                discountAmount = discountAmount,
                normalizedCurrency = normalizedCurrency,
                paidFor = paidFor
            )
        }

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

        // Resolve organizer's Stripe Connect account from tournament
        val connectedAccountId = resolveConnectedAccountOrFail(request.tournamentId)

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
                request.scheduleRestriction?.let {
                    putMetadata("schedule_restriction", Json.encodeToString(it))
                }
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
                    paymentLogger.info { "Payment routed to connected account $connectedAccountId (fee: $fee cents)" }
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
    private suspend fun resolveConnectedAccountOrFail(tournamentId: String): String? {
        val tournament = tournamentRepository.getById(tournamentId)
            ?: throw BadRequestException("Torneo no encontrado")
        val organizerId = tournament.organizerId ?: return null // No organizer — legacy flow

        val accountId = organizerRepository.getStripeAccountId(organizerId)
        if (accountId.isNullOrBlank()) return null // Organizer without Stripe Connect — legacy OK

        // Organizer HAS an account — validate it can receive charges
        return try {
            val status = stripeConnectService.getAccountStatus(accountId)
            if (!status.chargesEnabled) {
                throw BadRequestException(
                    "La cuenta de pagos del organizador aún no está habilitada. Contacta al organizador."
                )
            }
            accountId
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            paymentLogger.error { "Error validating Stripe account $accountId: ${e.message}" }
            throw BadRequestException(
                "Error al verificar la cuenta de pagos del organizador. Intenta de nuevo."
            )
        }
    }

    // -------------------------------------------------------
    // 🏷️ DISCOUNT: create intent with reduced amount
    // -------------------------------------------------------
    private suspend fun createPaymentIntentWithDiscount(
        request: PaymentRequest,
        userEmail: String?,
        originalAmount: Long,
        finalAmount: Long,
        discountAmount: Long,
        normalizedCurrency: String,
        paidFor: Int
    ): CreateIntentResponse {
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
        } else null

        val idem = UUID.randomUUID().toString()
        val connectedAccountId = resolveConnectedAccountOrFail(request.tournamentId)

        val params = PaymentIntentCreateParams.builder()
            .setAmount(finalAmount)
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
            .putMetadata("discount_code", request.discountCode ?: "")
            .putMetadata("original_amount", originalAmount.toString())
            .putMetadata("discount_amount", discountAmount.toString())
            .apply {
                request.scheduleRestriction?.let {
                    putMetadata("schedule_restriction", Json.encodeToString(it))
                }
                if (customer != null) {
                    setCustomer(customer.id)
                    setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION)
                }
                if (connectedAccountId != null) {
                    val fee = finalAmount * PLATFORM_FEE_PERCENT / 100
                    setApplicationFeeAmount(fee)
                    setTransferData(
                        PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(connectedAccountId)
                            .build()
                    )
                }
            }
            .build()

        val requestOptions = RequestOptions.builder().setIdempotencyKey(idem).build()
        val intent = PaymentIntent.create(params, requestOptions)

        val ephemeralKeySecret = customer?.let {
            try { createEphemeralKey(it.id).secret } catch (_: Exception) { null }
        }

        return CreateIntentResponse(
            paymentIntentClientSecret = intent.clientSecret,
            customerId = customer?.id,
            ephemeralKey = ephemeralKeySecret,
            originalAmount = originalAmount,
            discountApplied = discountAmount,
            finalAmount = finalAmount,
            discountCode = request.discountCode
        )
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

        // Validate code exists and belongs to the same organization as the tournament
        val validCode = registrationCodeRepository.getValidCode(code)
            ?: return RedeemOutcome.InvalidCode

        val codeOrgId = validCode.organizer_id
        if (codeOrgId != null) {
            val tournament = tournamentRepository.getById(tournamentId)
            val tournamentOrgId = tournament?.organizerId
            if (tournamentOrgId != null && codeOrgId != tournamentOrgId) {
                throw BadRequestException("Este código no es válido para este torneo.")
            }
        }

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
            categoryId = categoryId,
            paidFor = "1",
            method = "Código"
        )

        return if (myTeam == null) RedeemOutcome.Created else RedeemOutcome.Updated
    }

    private suspend fun getOrganizerOwnerEmail(tournamentId: String): String? {
        return try {
            val tournament = tournamentRepository.getById(tournamentId) ?: return null
            val organizerId = tournament.organizerId ?: return null
            val members = organizationTeamRepository.getMembers(organizerId)
            members.firstOrNull { it.role == "owner" }?.userEmail
        } catch (e: Exception) {
            paymentLogger.warn { "No se pudo obtener email del owner para torneo $tournamentId: ${e.message}" }
            null
        }
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
