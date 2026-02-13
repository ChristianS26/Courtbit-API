package com.incodap.repositories.payments

import models.payments.PaymentReportRowDto
import models.payments.RpcApplyCodeDto
import models.payments.RpcApplyStripePaymentDto

interface PaymentRepository {
    suspend fun applyStripePayment(dto: RpcApplyStripePaymentDto): Boolean
    suspend fun applyRegistrationCode(dto: RpcApplyCodeDto): Boolean
    suspend fun getPaymentsReport(tournamentId: String): List<PaymentReportRowDto>
    suspend fun isWebhookProcessed(eventId: String): Boolean
    suspend fun markWebhookProcessed(eventId: String, eventType: String): Boolean
}
