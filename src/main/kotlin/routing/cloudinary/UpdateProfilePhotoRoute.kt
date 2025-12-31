package com.incodap.routing.cloudinary

import com.incodap.repositories.users.UserRepository
import com.incodap.security.uid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import models.profile.UpdateProfilePhotoRequest

fun Route.uploadProfilePhotoRoute(
    userRepository: UserRepository,
) {
    authenticate("auth-jwt") {
        route("/auth/profile") {
            patch("/photo") {
                try {
                    val uid = call.uid
                    val body = call.receive<UpdateProfilePhotoRequest>()

                    if (body.photo_url.isBlank()) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "No se recibió la url de la imagen")
                        )
                    }

                    val updated = userRepository.updateProfilePhoto(uid, body.photo_url)
                    if (updated) {
                        call.respond(HttpStatusCode.OK, mapOf("photo_url" to body.photo_url))
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "No se pudo actualizar la foto")
                        )
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error interno del servidor"))
                    )
                }
            }
        }
    }
}
