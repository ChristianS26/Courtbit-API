package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class RedeemCodeRequest(
    val code: String,
    val tournamentId: String,
    val playerUid: String,
    val partnerUid: String,
    val categoryId: Int,
    val playerName: String? = null,   // opcional: solo para formatear restriction
    val restriction: String? = null   // si quieres "Juan: no puede el sábado", pásalo ya formateado o lo formateamos en ruta
)
