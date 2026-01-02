package com.incodap.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respond
import org.koin.ktor.ext.inject
import org.koin.java.KoinJavaComponent.inject as koinInject

const val ROLE_ADMIN = "admin"

val ApplicationCall.uid: String
    get() {
        val principal = principal<JWTPrincipal>()
        println("🔍 Principal completo: $principal")
        val uid = principal?.payload?.getClaim("uid")?.asString()
        println("🧾 Claim uid: $uid")
        return uid ?: throw IllegalArgumentException("UID no encontrado en el token")
    }

        val ApplicationCall.email: String
    get() = principal<JWTPrincipal>()?.getClaim("email", String::class) ?: ""

val ApplicationCall.role: String
    get() = principal<JWTPrincipal>()?.getClaim("role", String::class) ?: ""

suspend fun ApplicationCall.requireAdmin(): Boolean {
    if (this.role != ROLE_ADMIN) {
        this.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso solo para administradores"))
        return false
    }
    return true
}

suspend fun ApplicationCall.requireAdminUid(): String? {
    if (this.role != ROLE_ADMIN) {
        this.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso solo para administradores"))
        return null
    }
    return try {
        this.uid
    } catch (_: Exception) {
        this.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UID no encontrado en el token"))
        null
    }
}

suspend fun ApplicationCall.requireUserUid(): String? {
    val principal = this.principal<JWTPrincipal>()
    val uid = principal?.getClaim("uid", String::class)
    if (uid.isNullOrBlank()) {
        this.respond(HttpStatusCode.Unauthorized, mapOf("error" to "UID missing in token"))
        return null
    }
    return uid
}

suspend fun ApplicationCall.requireOrganizer(): String? {
    val uid = this.requireUserUid() ?: return null

    val organizerRepository = application.inject<repositories.organizer.OrganizerRepository>().value
    val organizer = organizerRepository.getByUserUid(uid)
    if (organizer == null) {
        this.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo usuarios con organización pueden realizar esta acción"))
        return null
    }

    return uid
}