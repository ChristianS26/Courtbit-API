package com.incodap.models.payments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import models.teams.ScheduleRestriction

@Serializable
data class PaymentRequest(
    val amount: Long,
    val currency: String,
    val restriction: String? = null,
    val playerName: String,
    val playerUid: String,
    val partnerUid: String,
    val tournamentId: String,
    val categoryId: Int,
    val email: String,
    val paidFor: String, // "1" o "2"
    val discountCode: String? = null,
    @SerialName("schedule_restriction") val scheduleRestriction: ScheduleRestriction? = null
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (amount <= 0) errors.add("Amount must be greater than 0.")
        if (currency.isBlank()) errors.add("Currency is required.")
        if (playerName.isBlank()) errors.add("Player name is required.")
        if (playerUid.isBlank()) errors.add("Player UID is required.")
        if (partnerUid.isBlank()) errors.add("Partner UID is required.")
        if (tournamentId.isBlank()) errors.add("Tournament ID is required.")
        if (email.isBlank()) errors.add("Email is required.")
        if (paidFor != "1" && paidFor != "2") errors.add("PaidFor must be '1' or '2'.")
        return errors
    }
}