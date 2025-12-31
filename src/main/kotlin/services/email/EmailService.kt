package services.email

import repositories.email.EmailRepository

class EmailService(
    private val repository: EmailRepository
) {

    suspend fun sendEmail(to: String, subject: String, htmlContent: String): Boolean {
        return repository.sendEmail(to, subject, htmlContent)
    }

    suspend fun sendExcelReportEmail(
        to: String,
        tournamentName: String,
        attachment: ByteArray
    ): Boolean {
        return repository.sendRegistrationsExcelReportEmail(to, tournamentName, attachment)
    }

    suspend fun sendRegistrationCodesExcelReportEmail(
        to: String,
        attachment: ByteArray
    ): Boolean {
        return repository.sendRegistrationCodesExcelReportEmail(to, attachment)
    }

    suspend fun sendPaymentsExcelReportEmail(
        to: String,
        tournamentName: String,
        attachment: ByteArray
    ): Boolean {
        return repository.sendPaymentsExcelReportEmail(to, tournamentName, attachment)
    }

    suspend fun sendRegistrationConfirmation(
        toEmail: String,
        playerName: String,
        partnerName: String?,
        tournamentName: String?,
        tournamentId: String,
        categoryName: String?,
        categoryId: Int,
        paidFor: String,
        method: String
    ): Boolean {
        val subject = "Confirmación de inscripción - ${tournamentName ?: "Torneo $tournamentId"}"
        val html = RegistrationEmailTemplates.playerConfirmationHtml(
            playerName = playerName,
            partnerName = partnerName,
            tournamentName = tournamentName,
            tournamentId = tournamentId,
            categoryName = categoryName,
            categoryId = categoryId,
            paidFor = paidFor,
            method = method
        )
        return repository.sendEmail(toEmail, subject, html)
    }

    suspend fun sendAdminNewRegistration(
        adminEmail: String,
        playerName: String,
        partnerName: String?,
        playerEmail: String,
        tournamentName: String?,
        tournamentId: String,
        categoryName: String?,
        categoryId: Int,
        paidFor: String,
        method: String
    ): Boolean {
        val subject = "Nueva inscripción confirmada - ${tournamentName ?: "Torneo $tournamentId"}"
        val html = RegistrationEmailTemplates.adminNotificationHtml(
            playerName = playerName,
            partnerName = partnerName,
            playerEmail = playerEmail,
            tournamentName = tournamentName,
            tournamentId = tournamentId,
            categoryName = categoryName,
            categoryId = categoryId,
            paidFor = paidFor,
            method = method
        )
        return repository.sendEmail(adminEmail, subject, html)
    }
}