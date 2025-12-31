package routing.remoteconfig

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import services.remoteconfig.RemoteConfigService

fun Route.remoteConfigRoutes(
    remoteConfigService: RemoteConfigService
) {
    route("/config/remote-config") {
        get {
            val platform = call.request.queryParameters["platform"] ?: "android"
            val config = remoteConfigService.getByPlatform(platform)
            if (config != null) {
                call.respond(config)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "No config found for platform: $platform")
                )
            }
        }
    }
}
