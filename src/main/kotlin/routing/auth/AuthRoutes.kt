// com/incodap/routing/auth/AuthRoutes.kt
package com.incodap.routing.auth

import com.incodap.security.uid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import models.auth.*
import services.auth.AuthService
import services.email.EmailService

fun Route.authRoutes(authService: AuthService, emailService: EmailService) {
    route("/auth") {

        post("/register") {
            try {
                val request = call.receive<RegisterRequest>()

                // ✅ Logs para validar que el front manda los campos esperados
                // (NO loguear password)
                call.application.environment.log.info(
                    "🟦 /auth/register payload: emailDomain={}, firstNameLen={}, lastNameLen={}, phonePresent={}, gender={}, birthdatePresent={}, countryIso={}, shirtSize={}",
                    request.email.substringAfter("@", missingDelimiterValue = "no-domain"),
                    request.firstName.length,
                    request.lastName.length,
                    request.phone != null,
                    request.gender ?: "null",
                    request.birthdate != null,
                    request.countryIso ?: "null",
                    request.shirtSize ?: "xs",
                )

                val response = authService.register(request)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: io.ktor.server.plugins.BadRequestException) {
                call.application.environment.log.warn("BadRequest (deserialization) on /auth/register: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request format: ${e.cause?.message ?: e.message}"))
            } catch (e: ValidationException) {
                call.application.environment.log.warn("Validation error on /auth/register: ${e.errors}")
                call.respond(HttpStatusCode.BadRequest, mapOf("errors" to e.errors))
            } catch (e: IllegalArgumentException) {
                call.application.environment.log.warn("IllegalArgument on /auth/register: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.application.environment.log.error("Unhandled on /auth/register", e)
                throw e
            }
        }


        post("/login") {
            try {
                val request = call.receive<LoginRequest>()
                val response = authService.login(request)
                call.respond(response)
            } catch (e: IllegalArgumentException) {
                call.application.environment.log.warn("Unauthorized on /auth/login: ${e.message}")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.application.environment.log.error("Unhandled on /auth/login", e)
                throw e
            }
        }

        authenticate("auth-jwt") {
            get("/search-users") {
                val query = call.request.queryParameters["query"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                if (query.length < 2) {
                    call.respond(HttpStatusCode.BadRequest, "La búsqueda debe tener al menos 2 caracteres.")
                    return@get
                }
                val users = authService.searchUsers(query, limit, offset)
                call.respond(users)
            }

            get("/search-org-players") {
                val organizerId = call.request.queryParameters["organizerId"]
                val query = call.request.queryParameters["query"].orEmpty()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                if (organizerId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "organizerId es requerido"))
                    return@get
                }
                if (query.length < 2) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "La búsqueda debe tener al menos 2 caracteres"))
                    return@get
                }
                val results = authService.searchOrgPlayers(organizerId, query, limit)
                call.respond(results)
            }

            get("/lookup-user") {
                val email = call.request.queryParameters["email"]
                val phone = call.request.queryParameters["phone"]

                if (email.isNullOrBlank() && phone.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Se requiere email o phone"))
                    return@get
                }
                val result = authService.lookupUser(email, phone)
                if (result != null) {
                    call.respond(result)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Usuario no encontrado"))
                }
            }

            patch("/change-password") {
                try {
                    val uid = call.uid
                    val request = call.receive<ChangePasswordRequest>()
                    val success = authService.changePassword(uid, request)
                    if (success) {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Contraseña actualizada correctamente"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo actualizar la contraseña"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.application.environment.log.warn("BadRequest on /auth/change-password: ${e.message}")
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.environment.log.error("Unhandled on /auth/change-password", e)
                    throw e
                }
            }
        }

        post("/forgot-password") {
            try {
                val request = call.receive<ForgotPasswordRequest>()
                authService.sendPasswordResetEmail(request.email.trim().lowercase(), emailService)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Se envió el correo para restablecer la contraseña"))
            } catch (e: IllegalArgumentException) {
                call.application.environment.log.warn("BadRequest on /auth/forgot-password: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.application.environment.log.error("Unhandled on /auth/forgot-password", e)
                throw e
            }
        }

        post("/reset-password") {
            try {
                val request = call.receive<ResetPasswordRequest>()
                if (request.newPassword.length < 8) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "La nueva contraseña debe tener al menos 8 caracteres.")
                    )
                }
                authService.resetPassword(request.token, request.newPassword)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Contraseña restablecida correctamente."))
            } catch (e: IllegalArgumentException) {
                call.application.environment.log.warn("BadRequest on /auth/reset-password: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.application.environment.log.error("Unhandled on /auth/reset-password", e)
                throw e
            }
        }
    }
}
