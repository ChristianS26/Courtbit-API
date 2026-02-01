package com.incodap.routing.city

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import services.city.CityService

fun Route.cityRoutes(
    cityService: CityService
) {
    // Public endpoints - cities catalog doesn't require auth
    route("/cities") {
        get {
            val cities = cityService.getAllCities()
            call.respond(cities)
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid city ID"))
                return@get
            }

            val city = cityService.getCityById(id)
            if (city != null) {
                call.respond(city)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "City not found"))
            }
        }
    }
}
