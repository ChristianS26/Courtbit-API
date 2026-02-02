package routing.auth

import com.incodap.models.users.toPublicUser
import com.incodap.repositories.users.UserRepository
import com.incodap.security.uid
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import models.profile.ProfileResponse
import models.profile.UpdateProfileRequest
import models.users.DeleteUserResult
import utils.JwtService

fun Route.profileRoute(
    userRepository: UserRepository,
    jwtService: JwtService
) {
    authenticate("auth-jwt") {
        route("/auth") {
            get("/me") {
                try {
                    val user = userRepository.findByUid(call.uid)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Usuario no encontrado")
                        )

                    val newAccessToken = jwtService.generateAccessToken(user.uid, user.email, user.role)
                    val publicUser = user.toPublicUser()

                    call.respond(HttpStatusCode.OK, ProfileResponse(user = publicUser, accessToken = newAccessToken))

                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error interno del servidor")
                    )
                }
            }


            patch("/profile") {
                try {
                    val uid = call.uid
                    val updateRequest = call.receive<UpdateProfileRequest>()


                    val updatedUser = userRepository.updateProfile(uid, updateRequest)

                    if (updatedUser != null) {
                        call.respond(
                            HttpStatusCode.OK,
                            updatedUser.toPublicUser()
                        ) // 👈 devuelve el usuario actualizado
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "No se pudo actualizar el perfil")
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error interno del servidor"))
                }
            }

            delete("/me") {
                try {
                    when (val result = userRepository.deleteByUid(call.uid)) {
                        DeleteUserResult.Deleted -> {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf("message" to "Cuenta eliminada exitosamente")
                            )
                        }

                        DeleteUserResult.NotFound -> {
                            call.respond(
                                HttpStatusCode.NotFound,
                                mapOf("error" to "Usuario no encontrado")
                            )
                        }

                        is DeleteUserResult.Error -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                mapOf("error" to result.message)
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error interno del servidor")
                    )
                }
            }
        }
    }
}
