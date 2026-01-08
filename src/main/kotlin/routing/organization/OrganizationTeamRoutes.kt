package routing.organization

import com.incodap.security.requireUserUid
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.organization.CreateInvitationRequest
import models.organization.JoinOrganizationRequest
import models.organization.RemoveMemberRequest
import services.organization.OrganizationTeamService

fun Route.organizationTeamRoutes(
    service: OrganizationTeamService
) {
    route("/organizations") {
        // All routes require authentication
        authenticate("auth-jwt") {

            // GET /organizations/me - Get organizations I belong to
            get("/me") {
                val uid = call.requireUserUid() ?: return@get

                val organizations = service.getUserOrganizations(uid)
                call.respond(HttpStatusCode.OK, organizations)
            }

            // POST /organizations/join - Join an organization with invitation code
            post("/join") {
                val uid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<JoinOrganizationRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body. Required: code, email, name")
                    )
                    return@post
                }

                val result = service.joinWithCode(request.code, uid, request.email, request.name)

                result.fold(
                    onSuccess = { joinResult ->
                        call.respond(HttpStatusCode.OK, joinResult)
                    },
                    onFailure = { error ->
                        when (error) {
                            is IllegalArgumentException -> {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to (error.message ?: "Invalid invitation code"))
                                )
                            }
                            else -> {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "Failed to join organization")
                                )
                            }
                        }
                    }
                )
            }

            // Routes scoped to a specific organizer
            route("/{organizerId}") {

                // GET /organizations/{organizerId}/members - Get organization members
                get("/members") {
                    val uid = call.requireUserUid() ?: return@get

                    val organizerId = call.parameters["organizerId"]
                    if (organizerId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Organizer ID is required")
                        )
                        return@get
                    }

                    val result = service.getMembers(organizerId, uid)

                    result.fold(
                        onSuccess = { members ->
                            call.respond(HttpStatusCode.OK, members)
                        },
                        onFailure = { error ->
                            when (error) {
                                is IllegalAccessException -> {
                                    call.respond(
                                        HttpStatusCode.Forbidden,
                                        mapOf("error" to (error.message ?: "Access denied"))
                                    )
                                }
                                else -> {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        mapOf("error" to "Failed to get members")
                                    )
                                }
                            }
                        }
                    )
                }

                // DELETE /organizations/{organizerId}/members - Remove a member
                delete("/members") {
                    val uid = call.requireUserUid() ?: return@delete

                    val organizerId = call.parameters["organizerId"]
                    if (organizerId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Organizer ID is required")
                        )
                        return@delete
                    }

                    val request = try {
                        call.receive<RemoveMemberRequest>()
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request body")
                        )
                        return@delete
                    }

                    val result = service.removeMember(request.memberId, organizerId, uid)

                    result.fold(
                        onSuccess = {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf("success" to true)
                            )
                        },
                        onFailure = { error ->
                            when (error) {
                                is IllegalAccessException -> {
                                    call.respond(
                                        HttpStatusCode.Forbidden,
                                        mapOf("error" to (error.message ?: "Access denied"))
                                    )
                                }
                                is IllegalArgumentException -> {
                                    call.respond(
                                        HttpStatusCode.NotFound,
                                        mapOf("error" to (error.message ?: "Member not found"))
                                    )
                                }
                                is IllegalStateException -> {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to (error.message ?: "Cannot remove member"))
                                    )
                                }
                                else -> {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        mapOf("error" to "Failed to remove member")
                                    )
                                }
                            }
                        }
                    )
                }

                // GET /organizations/{organizerId}/invitations - Get active invitations
                get("/invitations") {
                    val uid = call.requireUserUid() ?: return@get

                    val organizerId = call.parameters["organizerId"]
                    if (organizerId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Organizer ID is required")
                        )
                        return@get
                    }

                    val result = service.getInvitations(organizerId, uid)

                    result.fold(
                        onSuccess = { invitations ->
                            call.respond(HttpStatusCode.OK, invitations)
                        },
                        onFailure = { error ->
                            when (error) {
                                is IllegalAccessException -> {
                                    call.respond(
                                        HttpStatusCode.Forbidden,
                                        mapOf("error" to (error.message ?: "Access denied"))
                                    )
                                }
                                else -> {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        mapOf("error" to "Failed to get invitations")
                                    )
                                }
                            }
                        }
                    )
                }

                // POST /organizations/{organizerId}/invitations - Create new invitation
                post("/invitations") {
                    val uid = call.requireUserUid() ?: return@post

                    val organizerId = call.parameters["organizerId"]
                    if (organizerId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Organizer ID is required")
                        )
                        return@post
                    }

                    val result = service.createInvitation(organizerId, uid)

                    result.fold(
                        onSuccess = { invitation ->
                            call.respond(HttpStatusCode.Created, invitation)
                        },
                        onFailure = { error ->
                            when (error) {
                                is IllegalAccessException -> {
                                    call.respond(
                                        HttpStatusCode.Forbidden,
                                        mapOf("error" to (error.message ?: "Access denied"))
                                    )
                                }
                                else -> {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        mapOf("error" to "Failed to create invitation")
                                    )
                                }
                            }
                        }
                    )
                }

                // DELETE /organizations/{organizerId}/invitations/{invitationId} - Revoke invitation
                delete("/invitations/{invitationId}") {
                    val uid = call.requireUserUid() ?: return@delete

                    val organizerId = call.parameters["organizerId"]
                    val invitationId = call.parameters["invitationId"]

                    if (organizerId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Organizer ID is required")
                        )
                        return@delete
                    }

                    if (invitationId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invitation ID is required")
                        )
                        return@delete
                    }

                    val result = service.deleteInvitation(invitationId, organizerId, uid)

                    result.fold(
                        onSuccess = {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf("success" to true)
                            )
                        },
                        onFailure = { error ->
                            when (error) {
                                is IllegalAccessException -> {
                                    call.respond(
                                        HttpStatusCode.Forbidden,
                                        mapOf("error" to (error.message ?: "Access denied"))
                                    )
                                }
                                else -> {
                                    call.respond(
                                        HttpStatusCode.InternalServerError,
                                        mapOf("error" to "Failed to delete invitation")
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
