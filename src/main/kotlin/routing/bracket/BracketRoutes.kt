package routing.bracket

import com.incodap.security.getOrganizerId
import com.incodap.security.requireUserUid
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
import models.bracket.AssignGroupsRequest
import models.bracket.CreateBracketRequest
import models.bracket.ErrorResponse
import models.bracket.GenerateBracketRequest
import models.bracket.SuccessResponse
import models.bracket.SwapTeamsRequest
import models.bracket.UpdateScoreRequest
import models.bracket.UpdateStatusRequest
import models.bracket.UpdateBracketConfigRequest
import models.bracket.UpdateScheduleRequest
import models.bracket.WithdrawTeamRequest
import services.bracket.BracketService

/**
 * Bracket API routes
 */
fun Route.bracketRoutes(bracketService: BracketService) {
    route("/brackets") {

        // GET /api/brackets?tournament_id=xxx
        // Public - list all brackets for a tournament
        get {
            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val brackets = bracketService.getBracketsByTournament(tournamentId)
            call.respond(HttpStatusCode.OK, brackets)
        }

        // GET /api/brackets/{categoryId}?tournament_id=xxx
        // Public - anyone can view brackets
        get("/{categoryId}") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                return@get
            }

            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val bracket = bracketService.getBracket(tournamentId, categoryId)
            if (bracket != null) {
                call.respond(HttpStatusCode.OK, bracket)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bracket not found"))
            }
        }

        // GET /api/brackets/{categoryId}/players?tournament_id=xxx
        // Public - get players involved in bracket matches
        get("/{categoryId}/players") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                return@get
            }

            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val bracket = bracketService.getBracket(tournamentId, categoryId)
            if (bracket != null) {
                call.respond(HttpStatusCode.OK, bracket.players)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bracket not found"))
            }
        }

        // GET /api/brackets/{categoryId}/standings?tournament_id=xxx
        // Public - anyone can view standings for non-draft brackets
        get("/{categoryId}/standings") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                return@get
            }

            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val result = bracketService.getStandings(tournamentId, categoryId)

            result.fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respond(HttpStatusCode.NotFound, mapOf("error" to (it.message ?: "Standings not found"))) }
            )
        }

        // GET /api/brackets/{categoryId}/groups?tournament_id=xxx
        // Public - anyone can view groups state
        get("/{categoryId}/groups") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                return@get
            }

            val tournamentId = call.request.queryParameters["tournament_id"]
            if (tournamentId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                return@get
            }

            val result = bracketService.getGroupsState(tournamentId, categoryId)

            result.fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { e ->
                    when (e) {
                        is IllegalArgumentException -> call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to (e.message ?: "Groups not found"))
                        )
                        else -> call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Failed to get groups"))
                        )
                    }
                }
            )
        }

        authenticate("auth-jwt") {

            // POST /api/brackets/{categoryId}?tournament_id=xxx
            // Requires authentication - creates bracket without generating matches
            post("/{categoryId}") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<CreateBracketRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.localizedMessage}"))
                    return@post
                }

                // Validate seeding method
                if (request.seedingMethod !in listOf("random", "manual", "ranking")) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seeding_method must be 'random', 'manual', or 'ranking'"))
                    return@post
                }

                // Validate format - must match database constraint
                val validFormats = listOf("americano", "mexicano", "knockout", "round_robin", "groups_knockout")
                if (request.format !in validFormats) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "format must be one of: $validFormats"))
                    return@post
                }

                val result = bracketService.createBracket(
                    tournamentId = tournamentId,
                    categoryId = categoryId,
                    format = request.format,
                    seedingMethod = request.seedingMethod
                )

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Bracket creation failed"))) }
                )
            }

            // PATCH /api/brackets/{categoryId}?tournament_id=xxx
            // Requires authentication - updates bracket config (e.g. match format)
            patch("/{categoryId}") {
                val organizerId = call.getOrganizerId() ?: return@patch

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@patch
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@patch
                }

                val request = try {
                    call.receive<UpdateBracketConfigRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.localizedMessage}"))
                    return@patch
                }

                val result = bracketService.updateBracketConfig(
                    tournamentId = tournamentId,
                    categoryId = categoryId,
                    configJson = request.config.toString()
                )

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to (e.message ?: "Bracket not found"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Config update failed"))
                            )
                        }
                    }
                )
            }

            // POST /api/brackets/{categoryId}/generate?tournament_id=xxx
            // Requires authentication - generates bracket structure
            post("/{categoryId}/generate") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<GenerateBracketRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.localizedMessage}"))
                    return@post
                }

                // Validate request
                if (request.teamIds.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "team_ids cannot be empty"))
                    return@post
                }

                if (request.seedingMethod !in listOf("random", "manual", "ranking")) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "seeding_method must be 'random', 'manual', or 'ranking'"))
                    return@post
                }

                val result = bracketService.generateBracket(
                    tournamentId = tournamentId,
                    categoryId = categoryId,
                    seedingMethod = request.seedingMethod,
                    teamIds = request.teamIds
                )

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Bracket generation failed"))) }
                )
            }

            // POST /api/brackets/{categoryId}/publish?tournament_id=xxx
            // Requires authentication - publishes bracket (locks structure)
            post("/{categoryId}/publish") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.publishBracket(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Publish failed"))) }
                )
            }

            // POST /api/brackets/{categoryId}/unpublish?tournament_id=xxx
            // Requires authentication - unpublishes bracket (reverts to in_progress)
            post("/{categoryId}/unpublish") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.unpublishBracket(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Unpublish failed"))) }
                )
            }

            // DELETE /api/brackets/{categoryId}?tournament_id=xxx
            // Requires authentication - deletes bracket and all matches
            delete("/{categoryId}") {
                val organizerId = call.getOrganizerId() ?: return@delete

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@delete
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@delete
                }

                // Get bracket to find its ID
                val bracket = bracketService.getBracket(tournamentId, categoryId)
                if (bracket == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Bracket not found"))
                    return@delete
                }

                val deleted = bracketService.deleteBracket(bracket.bracket.id)
                if (deleted) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to delete bracket"))
                }
            }

            // POST /api/brackets/{categoryId}/standings/calculate?tournament_id=xxx
            // Requires authentication - recalculate standings from completed matches
            post("/{categoryId}/standings/calculate") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.calculateStandings(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to (e.message ?: "Cannot calculate standings"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Calculation failed"))
                            )
                        }
                    }
                )
            }

            // POST /api/brackets/{categoryId}/withdraw?tournament_id=xxx
            // Requires authentication - withdraw team and auto-forfeit remaining matches
            post("/{categoryId}/withdraw") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<WithdrawTeamRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.localizedMessage}"))
                    return@post
                }

                val result = bracketService.withdrawTeam(tournamentId, categoryId, request.teamId, request.reason)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to (e.message ?: "Cannot withdraw team"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Withdrawal failed"))
                            )
                        }
                    }
                )
            }

            // ============ Groups + Knockout Endpoints ============

            // POST /api/brackets/{categoryId}/groups/assign?tournament_id=xxx
            // Assign teams to groups and generate group stage matches
            post("/{categoryId}/groups/assign") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<AssignGroupsRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.localizedMessage}"))
                    return@post
                }

                request.groups.forEach { group ->
                }

                val result = bracketService.generateGroupStage(tournamentId, categoryId, request)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to (e.message ?: "Invalid group assignment"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Group generation failed"))
                            )
                        }
                    }
                )
            }

            // POST /api/brackets/{categoryId}/groups/swap?tournament_id=xxx
            // Swap two teams between groups
            post("/{categoryId}/groups/swap") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val request = try {
                    call.receive<SwapTeamsRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${e.localizedMessage}"))
                    return@post
                }

                val result = bracketService.swapTeamsInGroups(
                    tournamentId,
                    categoryId,
                    request.team1Id,
                    request.team2Id
                )

                result.fold(
                    onSuccess = {
                        // Return simple success - frontend will refetch the data
                        call.respond(HttpStatusCode.OK, SuccessResponse("Equipos intercambiados"))
                    },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(e.message ?: "Cannot swap teams")
                            )
                            is IllegalStateException -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse(e.message ?: "Swap not allowed")
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(e.message ?: "Swap failed")
                            )
                        }
                    }
                )
            }

            // POST /api/brackets/{categoryId}/groups/standings?tournament_id=xxx
            // Calculate group standings from completed matches
            post("/{categoryId}/groups/standings") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.calculateGroupStandings(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to (e.message ?: "Calculation failed"))
                        )
                    }
                )
            }

            // POST /api/brackets/{categoryId}/groups/generate-knockout?tournament_id=xxx
            // Generate knockout phase from group results
            post("/{categoryId}/groups/generate-knockout") {
                val organizerId = call.getOrganizerId() ?: return@post

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@post
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@post
                }

                val result = bracketService.generateKnockoutFromGroups(tournamentId, categoryId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.Created, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to (e.message ?: "Cannot generate knockout"))
                            )
                            is IllegalStateException -> call.respond(
                                HttpStatusCode.Conflict,
                                mapOf("error" to (e.message ?: "Knockout generation not allowed"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Generation failed"))
                            )
                        }
                    }
                )
            }

            // DELETE /api/brackets/{categoryId}/groups/knockout?tournament_id=xxx
            // Delete knockout phase (keeps group stage intact)
            delete("/{categoryId}/groups/knockout") {
                val organizerId = call.getOrganizerId() ?: return@delete

                val categoryId = call.parameters["categoryId"]?.toIntOrNull()
                if (categoryId == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid category ID"))
                    return@delete
                }

                val tournamentId = call.request.queryParameters["tournament_id"]
                if (tournamentId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "tournament_id query parameter required"))
                    return@delete
                }

                val result = bracketService.deleteKnockoutPhase(tournamentId, categoryId)

                result.fold(
                    onSuccess = {
                        call.respond(HttpStatusCode.OK, mapOf(
                            "success" to true,
                            "message" to "Knockout phase deleted successfully"
                        ))
                    },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to (e.message ?: "No knockout phase found"))
                            )
                            is IllegalStateException -> call.respond(
                                HttpStatusCode.Conflict,
                                mapOf("error" to (e.message ?: "Cannot delete knockout phase"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Deletion failed"))
                            )
                        }
                    }
                )
            }
        }
    }

    // ============ Match Routes ============

    route("/matches") {
        authenticate("auth-jwt") {

            // PATCH /api/matches/{id}/player-score
            // Submit score as a player (not organizer)
            patch("/{id}/player-score") {
                val userId = call.requireUserUid() ?: return@patch

                val matchId = call.parameters["id"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@patch
                }

                val request = try {
                    call.receive<UpdateScoreRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request format: ${e.localizedMessage}")
                    )
                    return@patch
                }

                val result = bracketService.submitPlayerScore(matchId, userId, request.sets)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        val message = e.message ?: "Update failed"
                        when {
                            e is IllegalAccessException ->
                                call.respond(HttpStatusCode.Forbidden, mapOf("error" to message))
                            e is IllegalArgumentException && message.contains("not found", ignoreCase = true) ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to message))
                            e is IllegalArgumentException ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to message))
                            else ->
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to message))
                        }
                    }
                )
            }

            // PATCH /api/matches/{id}/score
            // Update match score with padel validation
            patch("/{id}/score") {
                val organizerId = call.getOrganizerId() ?: return@patch

                val matchId = call.parameters["id"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@patch
                }

                val request = try {
                    call.receive<UpdateScoreRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request format: ${e.localizedMessage}")
                    )
                    return@patch
                }

                val result = bracketService.updateMatchScore(matchId, request.sets)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        val message = e.message ?: "Update failed"
                        when {
                            e is IllegalArgumentException && message.contains("not found", ignoreCase = true) ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to message))
                            e is IllegalArgumentException ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to message))
                            else ->
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to message))
                        }
                    }
                )
            }

            // POST /api/matches/{id}/advance
            // Advance winner to next match
            post("/{id}/advance") {
                val organizerId = call.getOrganizerId() ?: return@post

                val matchId = call.parameters["id"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@post
                }

                val result = bracketService.advanceWinner(matchId)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        when (e) {
                            is IllegalArgumentException -> call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to (e.message ?: "Cannot advance"))
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to (e.message ?: "Advancement failed"))
                            )
                        }
                    }
                )
            }

            // PATCH /api/matches/{id}/schedule
            // Update match schedule (court and time)
            patch("/{id}/schedule") {
                val organizerId = call.getOrganizerId() ?: return@patch

                val matchId = call.parameters["id"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@patch
                }

                val request = try {
                    call.receive<UpdateScheduleRequest>()
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request format: ${e.localizedMessage}")
                    )
                    return@patch
                }


                val result = bracketService.updateMatchSchedule(matchId, request.courtNumber, request.scheduledTime)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { e ->
                        val message = e.message ?: "Update failed"
                        when {
                            e is IllegalArgumentException && message.contains("not found", ignoreCase = true) ->
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to message))
                            e is IllegalArgumentException ->
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to message))
                            else ->
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to message))
                        }
                    }
                )
            }

            // DELETE /api/matches/{id}/score
            // Delete/reset match score
            delete("/{id}/score") {
                try {
                    val organizerId = call.getOrganizerId() ?: return@delete

                    val matchId = call.parameters["id"]
                    if (matchId.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Match ID required"))
                        return@delete
                    }

                    val result = bracketService.deleteMatchScore(matchId)

                    result.fold(
                        onSuccess = { call.respond(HttpStatusCode.OK, SuccessResponse("Score deleted")) },
                        onFailure = { e ->
                            val message = e.message ?: "Delete failed"
                            when {
                                e is IllegalArgumentException && message.contains("not found", ignoreCase = true) ->
                                    call.respond(HttpStatusCode.NotFound, ErrorResponse(message))
                                e is IllegalArgumentException ->
                                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                                else ->
                                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse(message))
                            }
                        }
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal error: ${e.message}"))
                }
            }

            // PATCH /api/matches/{id}/status
            // Update match status without changing score
            patch("/{id}/status") {
                val organizerId = call.getOrganizerId() ?: return@patch

                val matchId = call.parameters["id"]
                if (matchId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Match ID required"))
                    return@patch
                }

                val request = try {
                    call.receive<UpdateStatusRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
                    return@patch
                }

                val validStatuses = listOf("pending", "scheduled", "in_progress", "completed", "forfeit")
                if (request.status !in validStatuses) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid status. Valid: $validStatuses"))
                    return@patch
                }

                val result = bracketService.updateMatchStatus(matchId, request.status)

                result.fold(
                    onSuccess = { call.respond(HttpStatusCode.OK, it) },
                    onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to it.message)) }
                )
            }
        }
    }
}
