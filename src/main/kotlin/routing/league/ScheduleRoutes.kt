package routing.league

import com.incodap.security.requireOrganizer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.league.CreateMatchdayScheduleOverrideRequest
import models.league.CreateSeasonScheduleDefaultsRequest
import models.league.UpdateDayGroupAssignmentRequest
import models.league.UpdateMatchdayScheduleOverrideRequest
import models.league.UpdateSeasonScheduleDefaultsRequest
import repositories.league.*
import routing.ContentTypeException
import routing.receiveWithContentTypeCheck
import services.league.MasterScheduleService

fun Route.scheduleRoutes(
    defaultsRepository: SeasonScheduleDefaultsRepository,
    overridesRepository: MatchdayScheduleOverridesRepository,
    dayGroupRepository: DayGroupRepository,
    masterScheduleService: MasterScheduleService,
    seasonRepository: SeasonRepository
) {
    route("/schedule") {
        // Get master schedule for a category
        get("/category/{categoryId}") {
            val categoryId = call.parameters["categoryId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing category ID")
            )

            // Extract seasonId from query parameter
            val seasonId = call.request.queryParameters["season_id"] ?: return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing season_id query parameter")
            )

            val masterSchedule = masterScheduleService.getMasterSchedule(seasonId, categoryId)
            if (masterSchedule != null) {
                call.respond(HttpStatusCode.OK, masterSchedule)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Schedule not found"))
            }
        }

        authenticate("auth-jwt") {
            // Season schedule defaults
            route("/defaults") {
                // Get defaults for a season
                get("/season/{seasonId}") {
                    val seasonId = call.parameters["seasonId"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                    )

                    val defaults = defaultsRepository.getBySeasonId(seasonId)
                    if (defaults != null) {
                        call.respond(HttpStatusCode.OK, defaults)
                    } else {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Defaults not found"))
                    }
                }

                // Create defaults for a season
                post {
                    val uid = call.requireOrganizer() ?: return@post

                    val request = try {
                        call.receiveWithContentTypeCheck<CreateSeasonScheduleDefaultsRequest>()
                    } catch (e: ContentTypeException) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to e.message)
                        )
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request: ${e.localizedMessage}")
                        )
                    }

                    // Verify season ownership
                    val season = seasonRepository.getById(request.seasonId)
                    if (season == null) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Season not found")
                        )
                    }

                    // Check if defaults already exist
                    val existing = defaultsRepository.getBySeasonId(request.seasonId)
                    if (existing != null) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Defaults already exist for this season")
                        )
                    }

                    val created = defaultsRepository.create(request)
                    if (created != null) {
                        call.respond(HttpStatusCode.Created, created)
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to create defaults")
                        )
                    }
                }

                // Update defaults
                patch("/season/{seasonId}") {
                    val uid = call.requireOrganizer() ?: return@patch
                    val seasonId = call.parameters["seasonId"] ?: return@patch call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                    )

                    // Verify season ownership
                    val season = seasonRepository.getById(seasonId)
                    if (season == null) {
                        return@patch call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Season not found")
                        )
                    }

                    val request = try {
                        call.receiveWithContentTypeCheck<UpdateSeasonScheduleDefaultsRequest>()
                    } catch (e: ContentTypeException) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to e.message)
                        )
                    } catch (e: Exception) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request: ${e.localizedMessage}")
                        )
                    }

                    val updated = defaultsRepository.update(seasonId, request)
                    if (updated) {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to update defaults")
                        )
                    }
                }
            }

            // Matchday overrides
            route("/overrides") {
                // Get all overrides for a season
                get("/season/{seasonId}") {
                    val seasonId = call.parameters["seasonId"] ?: return@get call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing season ID")
                    )

                    val overrides = overridesRepository.getBySeasonId(seasonId)
                    call.respond(HttpStatusCode.OK, overrides)
                }

                // Create override
                post {
                    val uid = call.requireOrganizer() ?: return@post

                    val request = try {
                        call.receiveWithContentTypeCheck<CreateMatchdayScheduleOverrideRequest>()
                    } catch (e: ContentTypeException) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to e.message)
                        )
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request: ${e.localizedMessage}")
                        )
                    }

                    // Verify season ownership
                    val season = seasonRepository.getById(request.seasonId)
                    if (season == null) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Season not found")
                        )
                    }

                    // Check for existing override
                    val existing = overridesRepository.getBySeasonAndMatchday(
                        request.seasonId,
                        request.matchdayNumber
                    )
                    if (existing != null) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Override already exists for this matchday")
                        )
                    }

                    val created = overridesRepository.create(request)
                    if (created != null) {
                        call.respond(HttpStatusCode.Created, created)
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to create override")
                        )
                    }
                }

                // Update override
                patch("{id}") {
                    val uid = call.requireOrganizer() ?: return@patch
                    val id = call.parameters["id"] ?: return@patch call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing override ID")
                    )

                    val request = try {
                        call.receiveWithContentTypeCheck<UpdateMatchdayScheduleOverrideRequest>()
                    } catch (e: ContentTypeException) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to e.message)
                        )
                    } catch (e: Exception) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request: ${e.localizedMessage}")
                        )
                    }

                    val updated = overridesRepository.update(id, request)
                    if (updated) {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to update override")
                        )
                    }
                }

                // Delete override
                delete("{id}") {
                    val uid = call.requireOrganizer() ?: return@delete
                    val id = call.parameters["id"] ?: return@delete call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing override ID")
                    )

                    val deleted = overridesRepository.delete(id)
                    if (deleted) {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to delete override")
                        )
                    }
                }
            }

            // Day group assignments
            route("/assignments") {
                // Update day group assignment
                patch("/{dayGroupId}") {
                    val uid = call.requireOrganizer() ?: return@patch
                    val dayGroupId = call.parameters["dayGroupId"] ?: return@patch call.respond(
                        HttpStatusCode.BadRequest, mapOf("error" to "Missing day group ID")
                    )

                    val request = try {
                        call.receiveWithContentTypeCheck<UpdateDayGroupAssignmentRequest>()
                    } catch (e: ContentTypeException) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to e.message)
                        )
                    } catch (e: Exception) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid request: ${e.localizedMessage}")
                        )
                    }

                    val updated = dayGroupRepository.updateAssignment(dayGroupId, request)
                    if (updated) {
                        call.respond(HttpStatusCode.OK, mapOf("success" to true))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to update assignment")
                        )
                    }
                }
            }
        }
    }
}
