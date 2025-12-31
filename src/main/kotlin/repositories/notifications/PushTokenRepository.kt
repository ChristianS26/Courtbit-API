package repositories.notifications

interface PushTokenRepository {
    suspend fun upsertToken(
        userId: String,
        token: String,
        platform: String,
        deviceId: String?,
        flavor: String
    )
    suspend fun deleteToken(userId: String, token: String)
}
