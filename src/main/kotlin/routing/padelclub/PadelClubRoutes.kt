package com.incodap.routing.padelclub

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import services.padelclub.PadelClubService

fun Route.padelClubRoutes(
    padelClubService: PadelClubService
) {
    // Public endpoints - padel clubs catalog doesn't require auth
    route("/padel-clubs") {
        get {
            val cityId = call.request.queryParameters["city_id"]?.toIntOrNull()

            val clubs = if (cityId != null) {
                padelClubService.getPadelClubsByCityId(cityId)
            } else {
                padelClubService.getAllPadelClubs()
            }
            call.respond(clubs)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid padel club ID"))
                return@get
            }

            val club = padelClubService.getPadelClubById(id)
            if (club != null) {
                call.respond(club)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Padel club not found"))
            }
        }
    }
}
