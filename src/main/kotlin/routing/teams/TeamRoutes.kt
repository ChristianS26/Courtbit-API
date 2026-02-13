package com.incodap.routing.teams

import com.incodap.security.requireOrganizer
import com.incodap.services.excel.ExcelService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.incodap.models.teams.SetTeamResultRequest
import com.incodap.security.requireUserUid
import repositories.tournament.TournamentRepository
import models.ranking.TeamResultResponse
import models.teams.CheckTeamResponse
import models.teams.MarkPaymentRequest
import models.teams.RegisterTeamRequest
import models.teams.RegisteredInTournamentResponse
import models.teams.ReplacePlayerRequest
import models.teams.ReportRequest
import models.teams.TeamResultStatusResponse
import models.teams.UpdateTeamCategoryRequest
import services.email.EmailService
import services.teams.TeamService

fun Route.teamRoutes(
    teamService: TeamService,
    emailService: EmailService,
    excelService: ExcelService,
    tournamentRepository: TournamentRepository
) {
    route("/teams") {

        // Rutas públicas (sin autenticación)
        get("/check") {
            val tournamentId = call.request.queryParameters["tournamentId"]
            val playerA = call.request.queryParameters["playerA"]
            val playerB = call.request.queryParameters["playerB"]

            if (tournamentId.isNullOrBlank() || playerA.isNullOrBlank() || playerB.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Faltan parámetros obligatorios"))
                return@get
            }

            val team = teamService.teamExists(tournamentId, playerA, playerB)
            val response = if (team != null) {
                CheckTeamResponse(exists = true, teamId = team.id)
            } else {
                CheckTeamResponse(exists = false)
            }

            call.respond(HttpStatusCode.OK, response)
        }

        get("/by-tournament") {
            val tournamentId = call.request.queryParameters["tournamentId"]

            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Parámetro 'tournamentId' requerido"))
                return@get
            }

            val groupedTeams = teamService.getTeamsGroupedByCategoryWithPlayerInfo(tournamentId)
            call.respond(HttpStatusCode.OK, groupedTeams)
        }

        // Rutas protegidas con JWT
        authenticate("auth-jwt") {
            post("/register") {
                call.requireOrganizer() ?: return@post

                val request = try {
                    call.receive<RegisterTeamRequest>()
                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Invalid request format: ${e.localizedMessage}")
                    )
                    return@post
                }

                // Validate player A has either uid OR name
                val playerAHasIdentity = request.playerUid != null || !request.playerName.isNullOrBlank()
                if (!playerAHasIdentity) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Player A must have either playerUid or playerName")
                    )
                    return@post
                }

                // Validate player B has either uid OR name
                val playerBHasIdentity = request.partnerUid != null || !request.partnerName.isNullOrBlank()
                if (!playerBHasIdentity) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Player B must have either partnerUid or partnerName")
                    )
                    return@post
                }

                // If both are linked users, check they're not the same
                if (request.playerUid != null && request.partnerUid != null &&
                    request.playerUid == request.partnerUid) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Los jugadores no pueden ser iguales.")
                    )
                    return@post
                }

                val success = teamService.registerTeam(request)

                if (success) {
                    call.respond(
                        status = HttpStatusCode.OK,
                        message = mapOf("success" to true)
                    )
                } else {
                    call.respond(
                        status = HttpStatusCode.InternalServerError,
                        message = mapOf("error" to "No se pudo registrar o actualizar el equipo.")
                    )
                }
            }

            post("/register-free") {
                val uid = call.requireUserUid() ?: return@post

                val request = try {
                    call.receive<RegisterTeamRequest>()
                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Invalid request format: ${e.localizedMessage}")
                    )
                    return@post
                }

                // Verify tournament has payments disabled
                val tournament = tournamentRepository.getById(request.tournamentId)
                if (tournament == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Torneo no encontrado"))
                    return@post
                }
                if (tournament.paymentsEnabled) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Este torneo requiere pago para inscribirse")
                    )
                    return@post
                }

                // Validate: caller must be the player
                if (request.playerUid != uid) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo puedes registrarte a ti mismo"))
                    return@post
                }

                // Validate partner
                if (request.partnerUid.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Se requiere un compañero"))
                    return@post
                }

                if (request.playerUid == request.partnerUid) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Los jugadores no pueden ser iguales"))
                    return@post
                }

                val success = teamService.registerFreeTeam(request)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "No se pudo registrar el equipo")
                    )
                }
            }

            patch("/pay") {
                val adminUid = call.requireUserUid() ?: return@patch

                val reqRaw = try {
                    call.receive<MarkPaymentRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Formato inválido"))
                    return@patch
                }

                // Log opcional para depurar

                // 1) paidBy solo player_a | player_b
                if (reqRaw.paidBy != "player_a" && reqRaw.paidBy != "player_b") {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "paidBy debe ser 'player_a' o 'player_b'"))
                    return@patch
                }

                // 2) Normaliza method a minúsculas (si viene)
                val normalizedMethod = reqRaw.method?.trim()?.lowercase()
                val req = reqRaw.copy(method = normalizedMethod)

                // 3) Rama desmarcar (paid=false): no necesitamos method/tournamentId/playerUid
                if (!req.paid) {
                    val ok = teamService.markPaymentManual(req, adminUid)
                    if (ok) {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No se pudo desmarcar el pago"))
                    }
                    return@patch
                }

                if (req.tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournamentId requerido"))
                    return@patch
                }
                // Note: playerUid can be null for manual players (not linked to a CourtBit account)
                // The team is identified by teamId and player slot by paidBy (player_a/player_b)

                val ok = teamService.markPaymentManual(req, adminUid)
                if (ok) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No se pudo registrar el pago manual"))
                }
            }

            delete("{teamId}") {
                call.requireOrganizer() ?: return@delete

                val teamId = call.parameters["teamId"]

                if (teamId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "teamId es requerido"))
                    return@delete
                }

                val deleted = teamService.deleteTeam(teamId)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No se pudo eliminar el equipo"))
                }
            }

            post("/report") {
                call.requireOrganizer() ?: return@post

                val request = call.receive<ReportRequest>()
                val teamsByCategory = teamService.getTeamsGroupedByCategoryWithPlayerInfo(request.tournamentId)

                val excelFile = excelService.generateTeamsExcel(
                    tournamentName = request.tournamentName,
                    teamsByCategory = teamsByCategory
                )

                val sent = emailService.sendExcelReportEmail(
                    to = request.email,
                    tournamentName = request.tournamentName,
                    attachment = excelFile
                )

                if (sent) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Reporte enviado a tu correo"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo enviar el correo"))
                }
            }

            get("{teamId}") {
                val teamId = call.parameters["teamId"]
                if (teamId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "teamId requerido"))
                    return@get
                }

                val team = teamService.getTeamWithFullPlayerInfo(teamId)
                if (team == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Equipo no encontrado"))
                } else {
                    call.respond(HttpStatusCode.OK, team)
                }
            }

            patch("{teamId}/category") {
                call.requireOrganizer() ?: return@patch
                val teamId = call.parameters["teamId"]
                if (teamId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "teamId requerido"))
                    return@patch
                }

                val request = call.receive<UpdateTeamCategoryRequest>()
                val newCategoryId = request.categoryId

                if (newCategoryId == 0) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Nueva categoría requerida"))
                    return@patch
                }

                val success = teamService.updateTeamCategory(teamId, newCategoryId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo cambiar la categoría"))
                }
            }

            patch("{teamId}/replace-player") {
                call.requireOrganizer() ?: return@patch

                val teamId = call.parameters["teamId"]
                if (teamId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "teamId requerido"))
                    return@patch
                }

                val request = try {
                    call.receive<ReplacePlayerRequest>()
                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "Formato de solicitud inválido: ${e.localizedMessage}")
                    )
                    return@patch
                }

                // Validate player position
                if (request.playerPosition != "a" && request.playerPosition != "b") {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "playerPosition debe ser 'a' o 'b'")
                    )
                    return@patch
                }

                // Validate new player has identity
                val hasIdentity = request.newPlayerUid != null || !request.newPlayerName.isNullOrBlank()
                if (!hasIdentity) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = mapOf("error" to "El nuevo jugador debe tener uid o nombre")
                    )
                    return@patch
                }

                val success = teamService.replacePlayer(request.copy(teamId = teamId))
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "No se pudo reemplazar el jugador. Verifica que el jugador no esté duplicado en el equipo.")
                    )
                }
            }

            get("/status/by-tournament") {
                call.requireOrganizer() ?: return@get

                val tournamentId = call.request.queryParameters["tournamentId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing tournamentId")

                val data = teamService.getTeamsWithResultStatus(tournamentId)
                call.respond(HttpStatusCode.OK, data)
            }

            // 🔵 SET RESULT
            post("{teamId}/result") {
                call.requireOrganizer() ?: return@post

                val teamId = call.parameters["teamId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing teamId")

                val req = call.receive<SetTeamResultRequest>()
                try {
                    val teamResultId = teamService.setTeamResult(teamId, req)
                    call.respond(HttpStatusCode.OK, TeamResultResponse(teamResultId, "Result set"))
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("409") == true) {
                        call.respond(HttpStatusCode.Conflict, "Result already exists for this team in this tournament. Remove it first.")
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, "Error setting result: ${e.message}")
                    }
                }
            }

            // 🔵 UNSET RESULT
            delete("{teamId}/result") {
                call.requireOrganizer() ?: return@delete

                val teamId = call.parameters["teamId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing teamId")

                try {
                    teamService.unsetTeamResult(teamId)
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Result unset"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Error unsetting result: ${e.message}")
                }
            }

            get("{teamId}/result") {
                call.requireOrganizer() ?: return@get

                val teamId = call.parameters["teamId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing teamId")

                val status = teamService.getTeamResultStatus(teamId)

                val response = if (status != null) {
                    TeamResultStatusResponse(
                        teamId = teamId,
                        hasResult = true,
                        teamResultId = status.teamResultId,
                        position = status.position,
                        pointsAwarded = status.pointsAwarded,
                        season = status.season,
                        resultUpdatedAt = status.resultUpdatedAt
                    )
                } else {
                    TeamResultStatusResponse(
                        teamId = teamId,
                        hasResult = false
                    )
                }

                call.respond(HttpStatusCode.OK, response)
            }

            get("/me/by-tournament") {
                val uid = call.requireUserUid() ?: return@get
                val tournamentId = call.request.queryParameters["tournamentId"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournamentId requerido"))
                    return@get
                }

                val resp: RegisteredInTournamentResponse =
                    teamService.getUserRegistrationsInTournamentResponse(uid, tournamentId)

                call.respond(HttpStatusCode.OK, resp)
            }

            get("/me/registrations") {
                val uid = call.requireUserUid() ?: return@get

                val status   = call.request.queryParameters["status"] // active|upcoming|past|all
                val page     = call.request.queryParameters["page"]?.toIntOrNull()
                val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()

                val items = teamService.getMyRegistrations(
                    userUid = uid,
                    status  = status,
                    page    = page,
                    pageSize= pageSize
                )

                call.respond(HttpStatusCode.OK, items)
            }
        }
    }
}