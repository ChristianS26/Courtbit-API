package repositories.notifications

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.ByteArrayInputStream
import java.io.FileInputStream

object FirebaseAdmin {
    @Volatile private var initialized = false

    fun initIfNeeded() {
        if (initialized) return

        val jsonInline = System.getenv("FIREBASE_CREDENTIALS_JSON")
        val options = when {
            !jsonInline.isNullOrBlank() -> {
                val creds = GoogleCredentials.fromStream(ByteArrayInputStream(jsonInline.toByteArray()))
                FirebaseOptions.builder().setCredentials(creds).build()
            }
            else -> {
                val credsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")
                    ?: error("FIREBASE_CREDENTIALS_JSON o GOOGLE_APPLICATION_CREDENTIALS no configurados")
                val serviceAccount = FileInputStream(credsPath)
                val creds = GoogleCredentials.fromStream(serviceAccount)
                FirebaseOptions.builder().setCredentials(creds).build()
            }
        }
        FirebaseApp.initializeApp(options)
        initialized = true
    }
}
