package services.notifications

import com.google.firebase.messaging.FirebaseMessaging
import models.notifications.RegisterTokenRequest
import repositories.notifications.PushTokenRepository
import repositories.notifications.FirebaseAdmin

class PushService(
    private val repo: PushTokenRepository
) {
    private fun topicsFor(isAdmin: Boolean): List<String> =
        if (isAdmin) listOf("global", "admins") else listOf("global")

    suspend fun registerToken(userId: String, req: RegisterTokenRequest, isAdmin: Boolean) {
        repo.upsertToken(
            userId = userId,
            token = req.token,
            platform = req.platform,
            deviceId = req.deviceId,
            flavor = "prod" // puedes mantenerlo fijo en BD por consistencia
        )

        FirebaseAdmin.initIfNeeded()
        val fm = FirebaseMessaging.getInstance()
        topicsFor(isAdmin).forEach { fm.subscribeToTopic(listOf(req.token), it) }
    }


    suspend fun deleteToken(userId: String, token: String, isAdmin: Boolean) {
        repo.deleteToken(userId, token)
        FirebaseAdmin.initIfNeeded()
        val fm = FirebaseMessaging.getInstance()
        topicsFor(isAdmin).forEach { fm.unsubscribeFromTopic(listOf(token), it) }
    }
}
