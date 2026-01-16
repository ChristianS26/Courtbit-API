package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LeaguePaymentResponse(
    val id: String,
    @SerialName("league_player_id") val leaguePlayerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("category_id") val categoryId: String?,
    val amount: Long,
    val currency: String = "MXN",
    val method: String,
    val status: String,
    @SerialName("stripe_payment_id") val stripePaymentId: String? = null,
    @SerialName("stripe_customer_id") val stripeCustomerId: String? = null,
    val notes: String? = null,
    @SerialName("registered_by_uid") val registeredByUid: String? = null,
    @SerialName("registered_by_email") val registeredByEmail: String? = null,
    @SerialName("paid_at") val paidAt: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateLeaguePaymentRequest(
    @SerialName("league_player_id") val leaguePlayerId: String,
    @SerialName("season_id") val seasonId: String,
    @SerialName("category_id") val categoryId: String? = null,
    val amount: Long,
    val currency: String = "MXN",
    val method: String = "cash",
    val notes: String? = null,
    @SerialName("registered_by_email") val registeredByEmail: String? = null,
    @SerialName("paid_at") val paidAt: String? = null
)

@Serializable
data class UpdateLeaguePaymentRequest(
    val amount: Long? = null,
    val method: String? = null,
    val status: String? = null,
    val notes: String? = null,
    @SerialName("paid_at") val paidAt: String? = null
)

// Insert DTO for Supabase (snake_case)
@Serializable
data class LeaguePaymentInsertDto(
    val league_player_id: String,
    val season_id: String,
    val category_id: String? = null,
    val amount: Long,
    val currency: String = "MXN",
    val method: String = "cash",
    val status: String = "succeeded",
    val notes: String? = null,
    val registered_by_uid: String? = null,
    val registered_by_email: String? = null,
    val paid_at: String? = null
)

// Player payment summary (from RPC function)
@Serializable
data class PlayerPaymentSummary(
    @SerialName("total_paid") val totalPaid: Long,
    @SerialName("payment_count") val paymentCount: Int,
    @SerialName("last_payment_at") val lastPaymentAt: String? = null,
    @SerialName("registration_fee") val registrationFee: Long,
    @SerialName("discount_amount") val discountAmount: Long,
    @SerialName("effective_fee") val effectiveFee: Long,
    @SerialName("balance_due") val balanceDue: Long
)

// Season payment report row (from RPC function)
@Serializable
data class SeasonPaymentReportRow(
    @SerialName("player_id") val playerId: String,
    @SerialName("player_name") val playerName: String,
    @SerialName("player_email") val playerEmail: String? = null,
    @SerialName("player_phone") val playerPhone: String? = null,
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("registration_fee") val registrationFee: Long,
    @SerialName("discount_amount") val discountAmount: Long,
    @SerialName("discount_reason") val discountReason: String? = null,
    @SerialName("effective_fee") val effectiveFee: Long,
    @SerialName("total_paid") val totalPaid: Long,
    @SerialName("balance_due") val balanceDue: Long,
    @SerialName("payment_count") val paymentCount: Int,
    @SerialName("last_payment_at") val lastPaymentAt: String? = null,
    @SerialName("last_payment_method") val lastPaymentMethod: String? = null,
    @SerialName("is_fully_paid") val isFullyPaid: Boolean
)

// Request to update player discount
@Serializable
data class UpdatePlayerDiscountRequest(
    @SerialName("discount_amount") val discountAmount: Long,
    @SerialName("discount_reason") val discountReason: String? = null
)
