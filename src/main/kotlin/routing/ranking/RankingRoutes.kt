package routing.ranking

import com.incodap.security.requireOrganizer
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
                val season = call.request.queryParameters["season"]

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing userId")
                    return@get
                }
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing category")
                    return@get
                }

                try {
                    val profile = service.getPlayerProfile(userId, categoryId, season)
                    call.respond(profile)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error al obtener perfil: ${e.message}")
                }
            }

            // Admin: evento suelto por jugador (opcional, se mantiene)
            authenticate("auth-jwt") {
                post("/event") {
                    call.requireOrganizer() ?: return@post

                    val request = call.receive<AddRankingEventRequest>()
                    val resultId = service.addRankingEvent(request)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("message" to "Ranking event added", "eventId" to resultId)
                    )
                }

                // GET /api/ranking/check-existing?tournament_id=X&category_id=Y
                get("/check-existing") {
                    call.requireOrganizer() ?: return@get
                    val tournamentId = call.request.queryParameters["tournament_id"]
                    val categoryId = call.request.queryParameters["category_id"]?.toIntOrNull()
                    if (tournamentId.isNullOrBlank() || categoryId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing tournament_id or category_id"))
                        return@get
                    }
                    val exists = service.checkExistingEvents(tournamentId, categoryId)
                    call.respondText(
                        buildJsonObject { put("exists", exists) }.toString(),
                        ContentType.Application.Json
                    )
                }

                // POST /api/ranking/batch — batch assign ranking points
                post("/batch") {
                    call.requireOrganizer() ?: return@post

                    val request = call.receive<BatchRankingRequest>()
                    try {
                        val result = service.batchAddRankingEvents(request)
                        call.respondText(
                            buildJsonObject {
                                put("message", "Batch ranking events added")
                                put("inserted", result.inserted)
                                put("skipped", result.skipped)
                            }.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.Created
                        )
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to (e.message ?: "Invalid request"))
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
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
