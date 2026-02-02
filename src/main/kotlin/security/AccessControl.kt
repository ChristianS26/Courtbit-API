package com.incodap.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respond
import org.koin.ktor.ext.inject
import org.koin.java.KoinJavaComponent.inject as koinInject
import repositories.organizer.OrganizerRepository
import repositories.organization.OrganizationTeamRepository

const val ROLE_ADMIN = "admin"

val ApplicationCall.uid: String
    get() {
        val principal = principal<JWTPrincipal>()
        val uid = principal?.payload?.getClaim("uid")?.asString()
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

    // First check if user is an organizer owner
    val organizerRepository = application.inject<OrganizerRepository>().value
    val organizer = organizerRepository.getByUserUid(uid)
    if (organizer != null) {
        return uid
    }

    // If not an owner, check if user is a member of any organization
    val teamRepository = application.inject<OrganizationTeamRepository>().value
    val organizations = teamRepository.getUserOrganizations(uid)
    if (organizations.isNotEmpty()) {
        return uid
    }

    this.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo usuarios con organización pueden realizar esta acción"))
    return null
}

/**
 * Gets the organizer ID for the current user.
 * Works for both organizer owners and organization members.
 * Returns null and responds with error if user has no organization access.
 */
suspend fun ApplicationCall.getOrganizerId(): String? {
    val uid = this.requireUserUid() ?: return null

    // First check if user is an organizer owner
    val organizerRepository = application.inject<OrganizerRepository>().value
    val organizer = organizerRepository.getByUserUid(uid)
    if (organizer != null) {
        return organizer.id
    }

    // If not an owner, get from membership
    val teamRepository = application.inject<OrganizationTeamRepository>().value
    val organizations = teamRepository.getUserOrganizations(uid)
    val organizerId = organizations.firstOrNull()?.organizerId

    if (organizerId == null) {
        this.respond(HttpStatusCode.Forbidden, mapOf("error" to "No organizer profile"))
    }

    return organizerId
}

/**
 * Checks if user has access to a specific organizer (as owner or member).
 */
suspend fun ApplicationCall.hasAccessToOrganizer(organizerId: String): Boolean {
    val uid = this.requireUserUid() ?: return false

    // Check if user owns this organizer
    val organizerRepository = application.inject<OrganizerRepository>().value
    val organizer = organizerRepository.getByUserUid(uid)
    if (organizer?.id == organizerId) {
        return true
    }

    // Check if user is a member of this organization
    val teamRepository = application.inject<OrganizationTeamRepository>().value
    return teamRepository.userHasAccess(uid, organizerId)
}