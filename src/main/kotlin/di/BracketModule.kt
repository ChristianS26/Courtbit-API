package di

import org.koin.dsl.module
import repositories.bracket.BracketRepository
import repositories.bracket.BracketRepositoryImpl
import services.bracket.BracketService

val BracketModule = module {
    single<BracketRepository> { BracketRepositoryImpl(get(), get(), get()) }
    single { BracketService(get(), get()) }  // repository + json
}
