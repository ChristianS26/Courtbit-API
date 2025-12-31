package repositories.email

import com.incodap.services.payments.logger
import config.ResendConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import models.teams.Attachment
import models.teams.ResendEmailRequest
import java.util.Base64

class EmailRepositoryImpl(
    private val client: HttpClient,
    private val config: ResendConfig
) : EmailRepository {

    override suspend fun sendEmail(to: String, subject: String, htmlContent: String): Boolean {
        val response: HttpResponse = client.post("https://api.resend.com/emails") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
            }
            setBody(
                mapOf(
                    "from" to config.fromEmail,
                    "to" to to,
                    "subject" to subject,
                    "html" to htmlContent
                )
            )
        }

        val status = response.status
        val body = response.bodyAsText()
        println("📨 Resend status: $status")
        println("📨 Resend body: $body")

        return status.isSuccess()
    }

    override suspend fun sendRegistrationsExcelReportEmail(
        to: String,
        tournamentName: String,
        attachment: ByteArray
    ): Boolean {
        val sanitizedTournamentName = tournamentName.replace("[^a-zA-Z0-9-_]".toRegex(), "_")
        val fileName = "inscripciones-$sanitizedTournamentName.xlsx"
        val base64Attachment = Base64.getEncoder().encodeToString(attachment)

        val request = ResendEmailRequest(
            from = config.fromEmail,
            to = to,
            subject = "Reporte de inscripciones - $tournamentName",
            text = "Adjunto encontrarás el archivo Excel con las inscripciones por categoría.",
            attachments = listOf(
                Attachment(
                    filename = fileName,
                    content = base64Attachment,
                    content_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            )
        )

        val response: HttpResponse = client.post("https://api.resend.com/emails") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
            }
            setBody(request)
        }

        val status = response.status
        val body = response.bodyAsText()

        if (!status.isSuccess()) {
            logger.error("❌ Error al enviar email. Status: $status\nBody: $body")
            return false
        }

        logger.info("✅ Email enviado correctamente a $to")
        return true
    }

    override suspend fun sendRegistrationCodesExcelReportEmail(
        to: String,
        attachment: ByteArray
    ): Boolean {
        val fileName = "codigos-registro.xlsx"
        val base64Attachment = Base64.getEncoder().encodeToString(attachment)

        val request = ResendEmailRequest(
            from = config.fromEmail,
            to = to,
            subject = "Reporte de códigos de registro",
            text = """
            Adjunto encontrarás el archivo Excel con el resumen de códigos de registro:
            - Códigos utilizados por torneo (con información de quién los creó y quién los usó)
            - Lista de códigos aún no utilizados
        """.trimIndent(),
            attachments = listOf(
                Attachment(
                    filename = fileName,
                    content = base64Attachment,
                    content_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            )
        )

        val response: HttpResponse = client.post("https://api.resend.com/emails") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
            }
            setBody(request)
        }

        val status = response.status
        val body = response.bodyAsText()

        if (!status.isSuccess()) {
            logger.error("❌ Error al enviar email de códigos de registro. Status: $status\nBody: $body")
            return false
        }

        logger.info("✅ Email de códigos de registro enviado correctamente a $to")
        return true
    }

    override suspend fun sendPaymentsExcelReportEmail(
        to: String,
        tournamentName: String,
        attachment: ByteArray
    ): Boolean {
        val sanitizedTournamentName = tournamentName.replace("[^a-zA-Z0-9-_]".toRegex(), "_")
        val fileName = "pagos-$sanitizedTournamentName.xlsx"
        val base64Attachment = java.util.Base64.getEncoder().encodeToString(attachment)

        val request = models.teams.ResendEmailRequest(
            from = config.fromEmail,
            to = to,
            subject = "Reporte de pagos - $tournamentName",
            text = "Adjuntamos el archivo Excel con el detalle de pagos del torneo.",
            attachments = listOf(
                models.teams.Attachment(
                    filename = fileName,
                    content = base64Attachment,
                    content_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            )
        )

        val response: io.ktor.client.statement.HttpResponse =
            client.post("https://api.resend.com/emails") {
                headers {
                    append(io.ktor.http.HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    contentType(io.ktor.http.ContentType.Application.Json)
                }
                setBody(request)
            }

        val status = response.status
        val body = response.bodyAsText()
        if (!status.isSuccess()) {
            logger.error("❌ Error al enviar email de pagos. Status: $status\nBody: $body")
            return false
        }
        logger.info("✅ Email de pagos enviado correctamente a $to")
        return true
    }

}
