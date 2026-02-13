package di

import config.ResendConfig
import org.koin.dsl.module

val ResendModule = module {
    single {
        ResendConfig(
            apiKey = System.getenv("RESEND_API_KEY")
                ?: error("❌ RESEND_API_KEY no definida"),
            fromEmail = System.getenv("RESEND_FROM_EMAIL") ?: "noreply@courtbit.com"
        )
    }
}
