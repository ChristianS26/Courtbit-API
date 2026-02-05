package routing.ranking

import com.incodap.security.requireOrganizer
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.ranking.AddRankingEventRequest
import models.ranking.BatchRankingRequest
import services.ranking.RankingService

class RankingRoutes(
    private val service: RankingService
) {
    fun register(route: Route) {

        // ===== RUTAS DE RANKING (lecturas) =====
        route.route("/ranking") {
            get {
                val season = call.request.queryParameters["season"]
                val categoryId = call.request.queryParameters["category_id"]?.toIntOrNull()
                val organizerId = call.request.queryParameters["organizer_id"]
                val ranking = service.getRanking(season, categoryId, organizerId)
                call.respond(ranking)
            }

            get("/user/{userId}") {
                val userId = call.parameters["userId"]
                if (userId.isNullOrBlank()) {
                    call.respondText("Missing userId", status = HttpStatusCode.BadRequest)
                    return@get
                }
                val season = call.request.queryParameters["season"]
                val result = service.getRankingByUser(userId, season)
                call.respond(result)
            }

            get("/user/profile/{userId}") {
                val userId = call.parameters["userId"]
                val categoryId = call.request.queryParameters["category_id"]?.toIntOrNull()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing userId")
                    return@get
                }
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing category")
                    return@get
                }

                try {
                    val profile = service.getPlayerProfile(userId, categoryId)
                    call.respond(profile)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error al obtener perfil: ${e.message}")
                }
            }

            // Admin: evento suelto por jugador (opcional, se mantiene)
            route.authenticate("auth-jwt") {
                post("/event") {
                    call.requireOrganizer() ?: return@post

                    val request = call.receive<AddRankingEventRequest>()
                    val resultId = service.addRankingEvent(request)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("message" to "Ranking event added", "eventId" to resultId)
                    )
                }

                // POST /api/ranking/batch — batch assign ranking points
                post("/batch") {
                    call.requireOrganizer() ?: return@post

                    val request = call.receive<BatchRankingRequest>()
                    try {
                        val eventIds = service.batchAddRankingEvents(request)
                        call.respond(
                            HttpStatusCode.Created,
                            mapOf(
                                "message" to "Batch ranking events added",
                                "count" to eventIds.size,
                                "eventIds" to eventIds
                            )
                        )
                    } catch (e: Exception) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Error adding batch ranking events"))
                        )
                    }
                }
            }
        }
    }
}
