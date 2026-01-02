package di

import org.koin.dsl.module
import repositories.league.*
import services.league.LeagueCategoryService
import services.league.RankingService
import services.league.SeasonService

val LeagueModule = module {
    // Repositories
    single<SeasonRepository> { SeasonRepositoryImpl(get(), get(), get()) }
    single<LeagueCategoryRepository> { LeagueCategoryRepositoryImpl(get(), get(), get()) }
    single<LeaguePlayerRepository> { LeaguePlayerRepositoryImpl(get(), get(), get()) }
    single<MatchDayRepository> { MatchDayRepositoryImpl(get(), get(), get()) }
    single<DayGroupRepository> { DayGroupRepositoryImpl(get(), get(), get()) }
    single<RotationRepository> { RotationRepositoryImpl(get(), get(), get()) }
    single<DoublesMatchRepository> { DoublesMatchRepositoryImpl(get(), get(), get()) }

    // Services
    single { SeasonService(get(), get(), get()) }
    single { LeagueCategoryService(get(), get(), get(), get(), get()) }
    single { RankingService(get(), get(), get()) }
}
