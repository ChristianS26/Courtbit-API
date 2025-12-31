package models.payments// models/payments/PaymentInsertDto.kt
import kotlinx.serialization.Serializable

@Serializable
data class PaymentInsertDto(
    val team_id: String,
    val method: String,
    val tournament_id: String,
    val playerUid: String,
    val admin_uid: String,
    val amount: Long = 0L,
    val status: String = "succeeded"
)
