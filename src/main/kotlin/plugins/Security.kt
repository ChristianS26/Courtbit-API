package com.incodap.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import config.JwtConfig
import io.ktor.http.*

fun Application.configureSecurity() {
    val jwtSecret = System.getenv("JWT_SECRET")
        ?: error("❌ JWT_SECRET no configurada en variables de entorno")

    val jwtIssuer = JwtConfig.ISSUER
    val jwtAudience = JwtConfig.AUDIENCE

    install(Authentication) {
        jwt("auth-jwt") {
            realm = JwtConfig.REALM
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val uid = credential.payload.getClaim("uid").asString()
                val email = credential.payload.getClaim("email").asString()
                val role = credential.payload.getClaim("role").asString()

                if (!uid.isNullOrBlank() && !email.isNullOrBlank() && !role.isNullOrBlank()) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Token inválido o expirado")
                )
            }
        }
    }
}