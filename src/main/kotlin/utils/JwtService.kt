package utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import config.JwtConfig
import java.util.Date

class JwtService {

    val jwtSecret = System.getenv("JWT_SECRET")
        ?: error("❌ JWT_SECRET no está definida en variables de entorno")

    private val jwtIssuer = JwtConfig.ISSUER
    private val jwtAudience = JwtConfig.AUDIENCE

    fun generateAccessToken(uid: String, email: String, role: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("uid", uid)
            .withClaim("email", email)
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + JwtConfig.EXPIRATION_MILLIS))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
