package routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.SeasonShirtSizeRequest
import models.SeasonShirtSizesResponse
import models.ShirtSizeCatalogResponse
import org.koin.ktor.ext.inject
import repositories.ShirtSizeRepository
import com.incodap.security.requireOrganizer

fun Route.shirtSizeRoutes() {
    val shirtSizeRepository: ShirtSizeRepository by inject()

    route("/shirt-sizes") {
        // GET /api/shirt-sizes - Get all shirt sizes grouped by gender style
        get {
            val allSizes = shirtSizeRepository.getAll()

            val catalog = ShirtSizeCatalogResponse(
                unisex = allSizes.filter { it.genderStyle == "unisex" },
                mens = allSizes.filter { it.genderStyle == "mens" },
                womens = allSizes.filter { it.genderStyle == "womens" }
            )

            call.respond(HttpStatusCode.OK, catalog)
        }

        // GET /api/shirt-sizes/{genderStyle} - Get sizes for specific gender style
        get("/{genderStyle}") {
            val genderStyle = call.parameters["genderStyle"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing gender style")

            val validStyles = listOf("unisex", "mens", "womens")
            if (genderStyle !in validStyles) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Invalid gender style. Must be one of: ${validStyles.joinToString()}"
                )
            }

            val sizes = shirtSizeRepository.getByGenderStyle(genderStyle)
            call.respond(HttpStatusCode.OK, sizes)
        }

        // GET /api/shirt-sizes/season/{seasonId} - Get shirt sizes configured for a specific season
        get("/season/{seasonId}") {
            val seasonId = call.parameters["seasonId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing season ID")

            val sizes = shirtSizeRepository.getSeasonShirtSizes(seasonId)

            // If no season-specific config, return empty (fall back to global catalog on client)
            if (sizes.isEmpty()) {
                val response = SeasonShirtSizesResponse(
                    seasonId = seasonId,
                    availableStyles = emptyList(),
                    sizes = ShirtSizeCatalogResponse(emptyList(), emptyList(), emptyList())
                )
                return@get call.respond(HttpStatusCode.OK, response)
            }

            val availableStyles = sizes.map { it.genderStyle }.distinct()
            val catalog = ShirtSizeCatalogResponse(
                unisex = sizes.filter { it.genderStyle == "unisex" },
                mens = sizes.filter { it.genderStyle == "mens" },
                womens = sizes.filter { it.genderStyle == "womens" }
            )

            val response = SeasonShirtSizesResponse(
                seasonId = seasonId,
                availableStyles = availableStyles,
                sizes = catalog
            )

            call.respond(HttpStatusCode.OK, response)
        }

        // PUT /api/shirt-sizes/season/{seasonId} - Configure shirt sizes for a season (organizer only)
        authenticate("auth-jwt") {
            put("/season/{seasonId}") {
                val organizerId = call.requireOrganizer() ?: return@put

                val seasonId = call.parameters["seasonId"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing season ID")

                val request = call.receive<SeasonShirtSizeRequest>()

                val success = shirtSizeRepository.setSeasonShirtSizes(seasonId, request.shirtSizeIds)

                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Shirt sizes configured successfully"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to configure shirt sizes"))
                }
            }
        }
    }
}
