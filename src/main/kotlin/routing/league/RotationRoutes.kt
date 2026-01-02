package routing.league

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import repositories.league.RotationRepository

fun Route.rotationRoutes(
    rotationRepository: RotationRepository
) {
    route("/rotations") {
        // Get all rotations for a day group
        get("/by-day-group") {
            val dayGroupId = call.request.queryParameters["dayGroupId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "dayGroupId required"))

            val rotations = rotationRepository.getByDayGroupId(dayGroupId)
            call.respond(HttpStatusCode.OK, rotations)
        }

        // Get rotation by ID
        get("{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing rotation ID")
            )

            val rotation = rotationRepository.getById(id)
            if (rotation != null) {
                call.respond(HttpStatusCode.OK, rotation)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Rotation not found"))
            }
        }
    }
}
