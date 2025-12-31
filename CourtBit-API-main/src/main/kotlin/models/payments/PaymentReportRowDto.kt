package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class PaymentReportRowDto(
    val payment_id: String,
    val stripe_payment_id: String? = null,
    val amount: Long,
    val method: String,
    val status: String, // payment_status enum en texto
    val paid_at: String?, // ISO string
    val team_id: String? = null,
    val category_id: Int? = null,
    val category_name: String? = null,
    val player_uid: String? = null,
    val player_full_name: String? = null,
    val player_email: String? = null,
    val admin_uid: String? = null,
    val admin_email: String? = null,
)
