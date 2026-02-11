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
