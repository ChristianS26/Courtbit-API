package config

object JwtConfig {
    const val ISSUER = "incodap"
    const val AUDIENCE = "incodap_users"
    const val REALM = "Incodap Realm"
    const val EXPIRATION_MILLIS = 90 * 24 * 60 * 60 * 1000L // 30 Dias
}
