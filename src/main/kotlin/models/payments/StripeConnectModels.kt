package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class CreateConnectAccountResponse(val accountId: String)

@Serializable
data class AccountSessionResponse(val clientSecret: String)

@Serializable
data class ConnectAccountStatus(
    val accountId: String,
    val chargesEnabled: Boolean,
    val payoutsEnabled: Boolean,
    val onboardingComplete: Boolean,
    val requiresAction: Boolean
)

@Serializable
data class BalanceAmount(
    val amount: Long,
    val currency: String
)

@Serializable
data class ConnectBalance(
    val available: List<BalanceAmount>,
    val pending: List<BalanceAmount>
)

@Serializable
data class ConnectTransaction(
    val id: String,
    val amount: Long,
    val currency: String,
    val status: String,
    val description: String? = null,
    val customerEmail: String? = null,
    val created: Long,
    val platformFee: Long? = null,
    val stripeFee: Long? = null,
    val net: Long? = null,
    val paymentMethod: String? = null,
    val playerName: String? = null
)

@Serializable
data class ConnectPayout(
    val id: String,
    val amount: Long,
    val currency: String,
    val status: String,
    val arrivalDate: Long,
    val created: Long
)

@Serializable
data class ConnectBankAccount(
    val bankName: String?,
    val last4: String?,
    val currency: String?,
    val country: String?,
    val routingNumber: String?,
    val status: String?,
    val defaultForCurrency: Boolean = false
)

@Serializable
data class ConnectDashboardData(
    val balance: ConnectBalance,
    val transactions: List<ConnectTransaction>,
    val payouts: List<ConnectPayout>,
    val bankAccounts: List<ConnectBankAccount> = emptyList(),
    val stripeDashboardUrl: String? = null
)
