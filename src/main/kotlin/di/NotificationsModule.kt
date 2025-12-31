package di

import org.koin.dsl.module
import repositories.notifications.PushTokenRepository
import repositories.notifications.PushTokenRepositoryImpl
import services.notifications.PushService

val NotificationsModule = module {
    // Usa el mismo patrón que otros repos: get() para HttpClient, Json, SupabaseConfig
    single<PushTokenRepository> { PushTokenRepositoryImpl(get(), get(), get()) }
    single { PushService(get()) }
}
