package com.incodap.services.payments

import com.stripe.model.Account
import com.stripe.model.Balance
import com.stripe.model.BankAccount
import com.stripe.model.Charge
import com.stripe.model.LoginLink
import com.stripe.model.Payout
import com.stripe.model.Transfer
import com.stripe.net.RequestOptions
import com.stripe.param.AccountCreateParams
import com.stripe.param.ChargeRetrieveParams
import com.stripe.param.PayoutListParams
import com.stripe.param.TransferListParams
import models.payments.*
import mu.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val connectLogger = KotlinLogging.logger {}

class StripeConnectService(
    private val httpClient: HttpClient
) {
    private val stripeSecretKey: String = System.getenv("STRIPE_SECRET_KEY") ?: ""

    init {
        // Ensure Stripe.apiKey is set (in case this service initializes before PaymentService)
        if (stripeSecretKey.isNotBlank()) {
            com.stripe.Stripe.apiKey = stripeSecretKey
        }
    }

    /**
     * Creates a Stripe Express account for an organizer.
     * Returns the Stripe account ID (acct_xxx).
     */
    fun createExpressAccount(email: String, organizerId: String): String {
        val params = AccountCreateParams.builder()
            .setType(AccountCreateParams.Type.EXPRESS)
            .setEmail(email)
            .putMetadata("organizer_id", organizerId)
            .setCapabilities(
                AccountCreateParams.Capabilities.builder()
                    .setCardPayments(
                        AccountCreateParams.Capabilities.CardPayments.builder()
                            .setRequested(true)
                            .build()
                    )
                    .setTransfers(
                        AccountCreateParams.Capabilities.Transfers.builder()
                            .setRequested(true)
                            .build()
                    )
                    .build()
            )
            .build()

        val account = Account.create(params)
        connectLogger.info { "Created Stripe Express account ${account.id} for organizer $organizerId" }
        return account.id
    }

    /**
     * Creates an AccountSession for the embedded onboarding component.
     * Returns the client_secret to initialize ConnectJS.
     */
    suspend fun createAccountSession(accountId: String): String {
        val response = httpClient.post("https://api.stripe.com/v1/account_sessions") {
            header("Authorization", "Bearer $stripeSecretKey")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("account=$accountId&components[account_onboarding][enabled]=true")
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            connectLogger.error { "Failed to create account session: $body" }
            throw IllegalStateException("Failed to create Stripe account session")
        }

        val json = Json.parseToJsonElement(body).jsonObject
        val clientSecret = json["client_secret"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("No client_secret in Stripe response")

        connectLogger.info { "Created account session for Stripe account $accountId" }
        return clientSecret
    }

    /**
     * Gets the current status of a connected Stripe account.
     */
    fun getAccountStatus(accountId: String): ConnectAccountStatus {
        val account = Account.retrieve(accountId)

        val chargesEnabled = account.chargesEnabled ?: false
        val payoutsEnabled = account.payoutsEnabled ?: false
        val requirements = account.requirements
        val hasCurrentlyDue = requirements?.currentlyDue?.isNotEmpty() == true
        val hasPastDue = requirements?.pastDue?.isNotEmpty() == true

        return ConnectAccountStatus(
            accountId = accountId,
            chargesEnabled = chargesEnabled,
            payoutsEnabled = payoutsEnabled,
            onboardingComplete = chargesEnabled && payoutsEnabled,
            requiresAction = hasCurrentlyDue || hasPastDue
        )
    }

    /**
     * Gets the balance for a connected account (available + pending).
     */
    fun getBalance(accountId: String): ConnectBalance {
        val opts = RequestOptions.builder().setStripeAccount(accountId).build()
        val balance = Balance.retrieve(opts)

        return ConnectBalance(
            available = balance.available.map { BalanceAmount(it.amount, it.currency) },
            pending = balance.pending.map { BalanceAmount(it.amount, it.currency) }
        )
    }

    /**
     * Lists recent transactions for a connected account.
     *
     * Uses Transfer.list(destination=accountId) on the PLATFORM account,
     * then retrieves each source charge to get full details (metadata,
     * payment method, fees). Destination charges store all detail on the
     * platform charge, not on the connected account.
     */
    fun getTransactions(accountId: String, limit: Long = 20): List<ConnectTransaction> {
        val transferParams = TransferListParams.builder()
            .setDestination(accountId)
            .setLimit(limit)
            .build()
        val transfers = Transfer.list(transferParams) // platform scope

        return transfers.data.mapNotNull { transfer ->
            val sourceChargeId = transfer.sourceTransaction
            if (sourceChargeId.isNullOrBlank()) {
                // Fallback: transfer without a linked charge
                return@mapNotNull ConnectTransaction(
                    id = transfer.id,
                    amount = transfer.amount,
                    currency = transfer.currency,
                    status = "succeeded",
                    description = transfer.description,
                    customerEmail = null,
                    created = transfer.created,
                    platformFee = null,
                    stripeFee = null,
                    net = transfer.amount,
                    paymentMethod = null,
                    playerName = null
                )
            }

            try {
                val chargeParams = ChargeRetrieveParams.builder()
                    .addExpand("balance_transaction")
                    .build()
                val charge = Charge.retrieve(sourceChargeId, chargeParams, null)

                val balanceTx = charge.balanceTransactionObject
                val stripeFee = balanceTx?.feeDetails
                    ?.firstOrNull { it.type == "stripe_fee" }?.amount
                val appFee = balanceTx?.feeDetails
                    ?.firstOrNull { it.type == "application_fee" }?.amount
                    ?: charge.applicationFeeAmount

                val cardBrand = charge.paymentMethodDetails?.card?.brand
                val paymentMethodType = charge.paymentMethodDetails?.type
                val paymentMethod = cardBrand ?: paymentMethodType

                ConnectTransaction(
                    id = charge.id,
                    amount = charge.amount,
                    currency = charge.currency,
                    status = charge.status,
                    description = charge.description,
                    customerEmail = charge.billingDetails?.email
                        ?: charge.metadata?.get("email"),
                    created = charge.created,
                    platformFee = appFee,
                    stripeFee = stripeFee,
                    net = transfer.amount,
                    paymentMethod = paymentMethod,
                    playerName = charge.metadata?.get("player_name")
                )
            } catch (e: Exception) {
                connectLogger.warn { "Failed to retrieve charge $sourceChargeId: ${e.message}" }
                ConnectTransaction(
                    id = transfer.id,
                    amount = transfer.amount,
                    currency = transfer.currency,
                    status = "succeeded",
                    description = transfer.description,
                    customerEmail = null,
                    created = transfer.created,
                    platformFee = null,
                    stripeFee = null,
                    net = transfer.amount,
                    paymentMethod = null,
                    playerName = null
                )
            }
        }
    }

    /**
     * Lists recent payouts for a connected account.
     */
    fun getPayouts(accountId: String, limit: Long = 20): List<ConnectPayout> {
        val opts = RequestOptions.builder().setStripeAccount(accountId).build()
        val params = PayoutListParams.builder().setLimit(limit).build()
        val payouts = Payout.list(params, opts)

        return payouts.data.map { payout ->
            ConnectPayout(
                id = payout.id,
                amount = payout.amount,
                currency = payout.currency,
                status = payout.status,
                arrivalDate = payout.arrivalDate,
                created = payout.created
            )
        }
    }

    /**
     * Gets the external bank accounts configured on a connected account.
     */
    fun getBankAccounts(accountId: String): List<ConnectBankAccount> {
        return try {
            val account = Account.retrieve(accountId)
            val externals = account.externalAccounts?.data ?: return emptyList()

            externals.filterIsInstance<BankAccount>().map { bank ->
                ConnectBankAccount(
                    bankName = bank.bankName,
                    last4 = bank.last4,
                    currency = bank.currency,
                    country = bank.country,
                    routingNumber = bank.routingNumber,
                    status = bank.status,
                    defaultForCurrency = bank.defaultForCurrency ?: false
                )
            }
        } catch (e: Exception) {
            connectLogger.warn { "Failed to get bank accounts for $accountId: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Creates a login link to the Stripe Express Dashboard for the connected account.
     * The organizer can manage bank accounts, view payouts, etc. from there.
     */
    fun createExpressDashboardLink(accountId: String): String? {
        return try {
            val loginLink = LoginLink.createOnAccount(accountId, mapOf<String, Any>(), null)
            loginLink.url
        } catch (e: Exception) {
            connectLogger.warn { "Failed to create Express dashboard link for $accountId: ${e.message}" }
            null
        }
    }
}
