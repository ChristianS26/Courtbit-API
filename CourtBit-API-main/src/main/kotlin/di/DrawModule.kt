package di

import org.koin.dsl.module
import repositories.draw.DrawRepository
import repositories.draw.DrawRepositoryImpl
import services.draw.DrawService

val DrawModule = module {
    single<DrawRepository> { DrawRepositoryImpl(get(), get(), get()) }
    single { DrawService(get()) }
}
