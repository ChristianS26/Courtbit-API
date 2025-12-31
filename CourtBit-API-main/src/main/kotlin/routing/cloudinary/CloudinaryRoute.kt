package com.incodap.routing.cloudinary

import com.cloudinary.Cloudinary
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.cloudinaryRoutes() {
    // Cloudinary signature
    post("/cloudinary/sign-upload") {
        val cloudName = System.getenv("CLOUDINARY_CLOUD_NAME")
        val apiKey = System.getenv("CLOUDINARY_API_KEY")
        val apiSecret = System.getenv("CLOUDINARY_API_SECRET")

        val params = call.receiveParameters()
        val publicId = params["public_id"]
            ?: return@post call.respondText("Missing public_id", status = HttpStatusCode.BadRequest)
        val folder = params["folder"]
            ?: return@post call.respondText("Missing folder", status = HttpStatusCode.BadRequest)
        val overwrite = params["overwrite"] ?: "true"
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val paramsToSign = mapOf(
            "folder" to folder,
            "overwrite" to overwrite,
            "public_id" to publicId,
            "timestamp" to timestamp
        )

        val cloudinary = Cloudinary(
            mapOf(
                "cloud_name" to cloudName,
                "api_key" to apiKey,
                "api_secret" to apiSecret
            )
        )

        val signature = cloudinary.apiSignRequest(paramsToSign, apiSecret)

        call.respond(
            mapOf(
                "signature" to signature,
                "timestamp" to timestamp,
                "apiKey" to apiKey,
                "cloudName" to cloudName
            )
        )
    }
}