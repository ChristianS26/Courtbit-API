package routing.draw

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.common.ApiError
import models.common.ApiSuccess
import models.draw.DrawRequest
import services.draw.DrawService

fun Route.drawRoutes(drawService: DrawService) {
    authenticate("auth-jwt") {
        route("/tournament_details/draws") {
            get {
                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(error = "Missing tournamentId"))
                    return@get
                }
                val draws = drawService.getDrawsByTournament(tournamentId) // List<DrawResponse> (@Serializable)
                call.respond(draws)
            }

            post {
                try {
                    val drawRequest = call.receive<DrawRequest>()
                    val ok = drawService.createDraw(drawRequest)
                    if (ok == true) {
                        call.respond(HttpStatusCode.Created, ApiSuccess())
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ApiError(error = "No se pudo guardar el draw"))
                    }
                } catch (e: Exception) {
                    println("❌ Error deserializando/creando Draw: ${e.stackTraceToString()}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError(error = "Formato de payload inválido", exception = e.message)
                    )
                }
            }

            delete {
                val drawId = call.request.queryParameters["draw_id"]
                if (drawId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(error = "Missing draw_id"))
                    return@delete
                }
                val ok = drawService.deleteDraw(drawId)
                if (ok) call.respond(HttpStatusCode.OK, ApiSuccess())
                else call.respond(HttpStatusCode.InternalServerError, ApiError(error = "No se pudo eliminar el draw"))
            }
        }
    }
}
