package com.incodap.plugins

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.*
import kotlinx.serialization.json.Json

fun Application.configureSerialization() {
    val logger = environment.log

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = true
            }
        )
    }

    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(
                "Unhandled error on ${call.request.httpMethod.value} ${call.request.uri}",
                cause
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Error interno del servidor"))
            )
        }
    }
}
