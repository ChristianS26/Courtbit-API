package repositories.email

interface EmailRepository {
    suspend fun sendEmail(to: String, subject: String, htmlContent: String): Boolean
    suspend fun sendRegistrationsExcelReportEmail(to: String, tournamentName: String, attachment: ByteArray): Boolean
    suspend fun sendRegistrationCodesExcelReportEmail(to: String, attachment: ByteArray): Boolean
    suspend fun sendPaymentsExcelReportEmail(to: String, tournamentName: String, attachment: ByteArray): Boolean
}
