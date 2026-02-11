package com.incodap.services.payments

import com.stripe.model.Account
import com.stripe.model.Balance
import com.stripe.model.Charge
import com.stripe.model.Payout
import com.stripe.net.RequestOptions
import com.stripe.param.AccountCreateParams
import com.stripe.param.ChargeListParams
import com.stripe.param.PayoutListParams
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
     * Lists recent charges (transactions) for a connected account.
     */
    fun getTransactions(accountId: String, limit: Long = 20): List<ConnectTransaction> {
        val opts = RequestOptions.builder().setStripeAccount(accountId).build()
        val params = ChargeListParams.builder().setLimit(limit).build()
        val charges = Charge.list(params, opts)

        return charges.data.map { charge ->
            ConnectTransaction(
                id = charge.id,
                amount = charge.amount,
                currency = charge.currency,
                status = charge.status,
                description = charge.description,
                customerEmail = charge.billingDetails?.email,
                created = charge.created
            )
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
}
