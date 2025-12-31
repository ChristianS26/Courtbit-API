package com.incodap.routing.category

import com.incodap.security.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import models.category.TournamentCategoryRequest
import services.category.CategoryService

fun Route.categoryRoutes(
    categoryService: CategoryService
) {
    authenticate("auth-jwt") {
        route("/categories") {
            get {
                val tournamentId = call.request.queryParameters["tournament_id"]

                if (!tournamentId.isNullOrBlank()) {
                    val result = categoryService.getCategoriesForTournament(tournamentId)
                    call.respond(HttpStatusCode.OK, result)
                } else {
                    val categories = categoryService.getAllCategories()
                    call.respond(categories)
                }
            }

            post("/assign") {
               // if (!call.requireAdmin()) return@post

                val request = try {
                    call.receive<TournamentCategoryRequest>()
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Formato inválido: ${e.localizedMessage}")
                    )
                    return@post
                }

                val success = categoryService.assignCategoriesToTournament(request)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "No se pudieron asignar las categorías")
                    )
                }
            }
        }
    }
}
