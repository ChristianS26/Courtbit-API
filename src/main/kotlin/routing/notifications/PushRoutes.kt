package routing.notifications

import com.incodap.security.ROLE_ADMIN
import com.incodap.security.role
import com.incodap.security.uid
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.notifications.DeleteTokenRequest
import models.notifications.RegisterTokenRequest
import models.notifications.RegisterTokenResponse
import services.notifications.PushService

fun Route.pushRoutes(
     pushService: PushService
) {
    fun Route.install() {
        authenticate("auth-jwt") {
            route("/api/push") {

                // routing/notifications/PushRoutes.kt
                post("/token") {
                    val userId = call.uid
                    val isAdmin = (call.role == ROLE_ADMIN)
                    val req = call.receive<RegisterTokenRequest>()
                    pushService.registerToken(userId, req, isAdmin)
                    call.respond(RegisterTokenResponse(ok = true))
                }

                delete("/token") {
                    val userId = call.uid
                    val isAdmin = (call.role == ROLE_ADMIN)
                    val req = call.receive<DeleteTokenRequest>()
                    pushService.deleteToken(userId, req.token, isAdmin)
                    call.respond(RegisterTokenResponse(ok = true))
                }
            }
        }
    }
}
