package com.incodap.routing.club

import com.incodap.models.club.*
import com.incodap.services.club.ClubService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.clubRoutes(clubService: ClubService) {
    route("/clubs") {
        authenticate("auth-jwt") {
            // GET /clubs - List organizer's clubs
            get {
                val principal = call.principal<JWTPrincipal>()
                val organizerId = principal?.payload?.getClaim("sub")?.asString()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, "Missing user ID")

                val clubs = clubService.getClubs(organizerId)
                call.respond(clubs)
            }

            // POST /clubs - Create new club
            post {
                val principal = call.principal<JWTPrincipal>()
                val organizerId = principal?.payload?.getClaim("sub")?.asString()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, "Missing user ID")

                val request = call.receive<CreateClubRequest>()
                val club = clubService.createClub(organizerId, request)
                call.respond(HttpStatusCode.Created, club)
            }

            // GET /clubs/{id} - Get club with courts
            get("{id}") {
                val clubId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing club ID")

                val club = clubService.getClubWithCourts(clubId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Club not found")

                call.respond(club)
            }

            // PATCH /clubs/{id} - Update club
            patch("{id}") {
                val clubId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing club ID")

                val request = call.receive<UpdateClubRequest>()
                val club = clubService.updateClub(clubId, request)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, "Club not found")

                call.respond(club)
            }

            // DELETE /clubs/{id} - Delete club
            delete("{id}") {
                val clubId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing club ID")

                val deleted = clubService.deleteClub(clubId)
                if (deleted) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Club not found")
                }
            }

            // Club courts routes
            route("{clubId}/courts") {
                // GET /clubs/{clubId}/courts - List club courts
                get {
                    val clubId = call.parameters["clubId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing club ID")

                    val courts = clubService.getClubCourts(clubId)
                    call.respond(courts)
                }

                // POST /clubs/{clubId}/courts - Add court to club
                post {
                    val clubId = call.parameters["clubId"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing club ID")

                    val request = call.receive<CreateClubCourtRequest>()
                    val court = clubService.createClubCourt(clubId, request)
                    call.respond(HttpStatusCode.Created, court)
                }

                // PATCH /clubs/{clubId}/courts/{courtId} - Update court
                patch("{courtId}") {
                    val courtId = call.parameters["courtId"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Missing court ID")

                    val request = call.receive<UpdateClubCourtRequest>()
                    val court = clubService.updateClubCourt(courtId, request)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, "Court not found")

                    call.respond(court)
                }

                // DELETE /clubs/{clubId}/courts/{courtId} - Delete court
                delete("{courtId}") {
                    val courtId = call.parameters["courtId"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing court ID")

                    val deleted = clubService.deleteClubCourt(courtId)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Court not found")
                    }
                }
            }
        }
    }
}
