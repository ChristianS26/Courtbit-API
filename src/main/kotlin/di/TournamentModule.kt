package di

import org.koin.dsl.module
import repositories.tournament.TournamentRepository
import repositories.tournament.TournamentRepositoryImpl
import services.tournament.TournamentService

val TournamentModule = module {
    single<TournamentRepository> { TournamentRepositoryImpl(get(), get(), get()) }
    single { TournamentService(get(), get(), get()) }
}
