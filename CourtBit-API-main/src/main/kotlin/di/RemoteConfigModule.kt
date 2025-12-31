package di

import org.koin.dsl.module
import repositories.remoteconfig.RemoteConfigRepository
import repositories.remoteconfig.RemoteConfigRepositoryImpl
import services.remoteconfig.RemoteConfigService

val RemoteConfigModule = module {

    single<RemoteConfigRepository> {
        RemoteConfigRepositoryImpl(get(), get(), get())
    }

    single {
        RemoteConfigService(repository = get())
    }
}
