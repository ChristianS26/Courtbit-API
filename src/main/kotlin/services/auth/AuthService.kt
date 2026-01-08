package services.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.incodap.models.users.UserDto
import com.incodap.models.users.toPublicUser
import com.incodap.repositories.users.UserRepository
import config.JwtConfig
import models.auth.*
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import services.email.EmailService
import utils.JwtService
import java.util.UUID

class AuthService(
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val jwtIssuer = JwtConfig.ISSUER
    private val jwtAudience = JwtConfig.AUDIENCE

    suspend fun searchUsers(query: String) = userRepository.searchUsers(query)

    fun hashPassword(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())
    fun verifyPassword(plain: String, hash: String): Boolean = BCrypt.checkpw(plain, hash)

    suspend fun register(request: RegisterRequest): AuthResponse {
        val validationErrors = request.validate()
        if (validationErrors.isNotEmpty()) {
            val msg = validationErrors.joinToString(" ")
            log.warn("⚠️ Validation error on register: {}", msg)
            throw IllegalArgumentException(msg)
        }

        val normalizedEmail = request.email.trim().lowercase()

        // Duplicado temprano
        userRepository.findByEmail(normalizedEmail)?.let {
            log.warn("⚠️ Email ya registrado (pre-insert): {}", normalizedEmail)
            throw IllegalArgumentException("Email ya registrado.")
        }

        val userDto = UserDto(
            uid = UUID.randomUUID().toString(),
            email = normalizedEmail,
            passwordHash = hashPassword(request.password),
            firstName = request.firstName,
            lastName = request.lastName,
            phone = request.phone,
            gender = request.gender,
            birthdate = request.birthdate,
            role = "user",
            photoUrl = "",
            countryIso = request.countryIso,
            shirtSize = request.shirtSize,
        )

        val inserted = try {
            userRepository.insertUser(userDto)
        } catch (e: Exception) {
            // Mapeo de mensajes típicos que vienen desde Supabase/PostgREST/DB
            val msg = (e.message ?: "").lowercase()
            when {
                // unique constraint / duplicate
                "duplicate" in msg || "unique" in msg || "409" in msg -> {
                    log.warn("⚠️ Duplicate on insert (repo threw). email={}", normalizedEmail, e)
                    throw IllegalArgumentException("Email ya registrado.")
                }
                // RLS / permisos / key errónea
                "permission denied" in msg || "row-level security" in msg || "rls" in msg || "401" in msg || "403" in msg -> {
                    log.error("❌ Access denied inserting user (RLS/Key).", e)
                    throw IllegalStateException("Acceso denegado por la base de datos. Revisa service_role o policies.")
                }
                else -> {
                    log.error("❌ Repo insertUser lanzó excepción no mapeada.", e)
                    throw IllegalStateException("No se pudo registrar el usuario. Intenta más tarde.")
                }
            }
        }

        if (!inserted) {
            // El repo devolvió false sin excepción → checamos si el usuario quedó creado
            val now = userRepository.findByEmail(normalizedEmail)
            if (now != null) {
                log.warn("⚠️ insertUser() devolvió false pero el usuario sí existe; continuando. email={}", normalizedEmail)
                val token = jwtService.generateAccessToken(now.uid, now.email, now.role)
                return AuthResponse(token, now.toPublicUser())
            }
            log.error("❌ insertUser() devolvió false y el usuario no existe. Posible RLS/constraint sin propagar.")
            throw IllegalStateException("No se pudo registrar el usuario. Intenta más tarde.")
        }

        val token = jwtService.generateAccessToken(userDto.uid, userDto.email, userDto.role)
        return AuthResponse(token = token, user = userDto.toPublicUser())
    }

    suspend fun login(request: LoginRequest): AuthResponse {
        val normalizedEmail = request.email.trim().lowercase()

        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw IllegalArgumentException("Email o contraseña inválidos")

        if (!verifyPassword(request.password, user.passwordHash)) {
            throw IllegalArgumentException("Email o contraseña inválidos")
        }

        val token = jwtService.generateAccessToken(user.uid, user.email, user.role)
        log.info("✅ Login ok for {}", normalizedEmail)
        return AuthResponse(token, user.toPublicUser())
    }

    suspend fun changePassword(uid: String, request: ChangePasswordRequest): Boolean {
        val user = userRepository.findByUid(uid) ?: throw IllegalArgumentException("Usuario no encontrado")
        if (!BCrypt.checkpw(request.currentPassword, user.passwordHash)) {
            throw IllegalArgumentException("La contraseña actual no es correcta")
        }
        val newHash = BCrypt.hashpw(request.newPassword, BCrypt.gensalt())
        return userRepository.updatePassword(uid, newHash)
    }

    suspend fun sendPasswordResetEmail(email: String, emailService: EmailService) {
        val user = userRepository.findByEmail(email) ?: throw IllegalArgumentException("Usuario no encontrado")

        val newPassword = generateRandomPassword()
        val hashedPassword = hashPassword(newPassword)
        userRepository.updatePassword(user.uid, hashedPassword)

        val html = """
            <p>Hola ${user.firstName},</p>
            <p>Hemos generado una nueva contraseña temporal para tu cuenta:</p>
            <p style="font-size: 18px; font-weight: bold;">$newPassword</p>
            <p>Puedes usarla para ingresar a la app. Te recomendamos cambiarla lo antes posible desde tu perfil.</p>
            <p>Si tú no solicitaste este cambio, puedes ignorar este mensaje o contactar con soporte.</p>
        """.trimIndent()

        val enviado = emailService.sendEmail(
            to = user.email,
            subject = "Nueva contraseña temporal",
            htmlContent = html
        )
        if (!enviado) throw IllegalStateException("No se pudo enviar el correo de recuperación")
    }

    suspend fun resetPassword(token: String, newPassword: String) {
        val verifier = JWT.require(Algorithm.HMAC256(jwtService.jwtSecret))
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .build()

        val decoded = verifier.verify(token)
        val uid = decoded.getClaim("uid").asString()
        val user = userRepository.findByUid(uid) ?: throw IllegalArgumentException("Usuario no encontrado")

        val newHash = hashPassword(newPassword)
        val success = userRepository.updatePassword(uid, newHash)
        if (!success) throw IllegalStateException("No se pudo actualizar la contraseña")
    }

    fun generateRandomPassword(length: Int = 10): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#\$%&"
        return (1..length).map { allowedChars.random() }.joinToString("")
    }
}
