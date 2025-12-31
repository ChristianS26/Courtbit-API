package com.incodap.repositories.payments

import models.payments.PaymentReportRowDto
import models.payments.RpcApplyCodeDto
import models.payments.RpcApplyStripePaymentDto

interface PaymentRepository {
    suspend fun applyStripePayment(dto: RpcApplyStripePaymentDto): Boolean
    suspend fun applyRegistrationCode(dto: RpcApplyCodeDto): Boolean
    suspend fun getPaymentsReport(tournamentId: String): List<PaymentReportRowDto>
}
