package models.payments

@kotlinx.serialization.Serializable
data class RpcApplyStripePaymentDto(
    val p_stripe_payment_id: String,
    val p_amount: Long,
    val p_tournament_id: String,
    val p_player_uid: String,
    val p_partner_uid: String,
    val p_category_id: Int,
    val p_paid_for: String,        // "1" | "2"
    val p_customer_id: String? = null,
    val p_restriction: String? = null,
)
