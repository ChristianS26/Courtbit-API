package com.incodap.routing.registrationcode

import com.incodap.security.email
import com.incodap.security.requireAdmin
import com.incodap.security.requireUserUid
import com.incodap.services.excel.ExcelService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.registrationcode.RegistrationCodeReportRequest
import repositories.organizer.OrganizerRepository
import services.email.EmailService
import services.registrationcode.RegistrationCodeService

fun Route.registrationCodeRoutes(
    registrationCodeService: RegistrationCodeService,
    emailService: EmailService,
    excelService: ExcelService,
    organizerRepository: OrganizerRepository
) {
    route("/registration-codes") {
        authenticate("auth-jwt") {
            post("/create") {
                try {
                    val email = call.email.ifBlank {
                        throw IllegalArgumentException("Email no encontrado en el token")
                    }

                    // Obtener UID del usuario autenticado
                    val uid = call.requireUserUid()

                    // Obtener el organizer_id del usuario (si es organizador)
                    val organizer = uid?.let { organizerRepository.getByUserUid(it) }
                    val organizerId = organizer?.id

                    val code = registrationCodeService.createRegistrationCode(email, organizerId)
                    call.respond(HttpStatusCode.Created, mapOf("code" to code))
                } catch (e: Exception) {
                    println("❌ Error al crear código: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, "Error al crear código")
                }
            }

            get {
                try {
                    // Query parameter for filtering by organizer
                    val organizerId = call.request.queryParameters["organizer_id"]

                    val codes = if (organizerId != null) {
                        registrationCodeService.getRegistrationCodesByOrganizer(organizerId)
                    } else {
                        registrationCodeService.getAllRegistrationCodes()
                    }

                    call.respond(HttpStatusCode.OK, codes)
                } catch (e: Exception) {
                    println("❌ Error obteniendo códigos: ${e.message}")
                    call.respond(HttpStatusCode.InternalServerError, "Error al obtener códigos")
                }
            }

            post("/report") {
                if (!call.requireAdmin()) return@post

                val request = call.receive<RegistrationCodeReportRequest>() // Solo necesitas el correo, no el torneo
                val codes = registrationCodeService.getAllRegistrationCodesWithTournamentInfo()

                val excelFile = excelService.generateRegistrationCodesExcel(
                    codes = codes // Ya no necesitas el nombre del torneo
                )

                val sent = emailService.sendRegistrationCodesExcelReportEmail(
                    to = request.email,
                    attachment = excelFile
                )

                if (sent) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Reporte enviado a tu correo"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo enviar el correo"))
                }
            }

        }
    }
}
