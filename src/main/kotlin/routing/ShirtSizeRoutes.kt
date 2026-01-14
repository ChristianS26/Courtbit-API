package routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import models.ShirtSizeCatalogResponse
import org.koin.ktor.ext.inject
import repositories.ShirtSizeRepository

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
    }
}
