package di

import org.koin.dsl.module
import repositories.league.*
import services.league.AutoSchedulingService
import services.league.LeagueCategoryService
import services.league.LeaguePaymentService
import services.league.MasterScheduleService
import services.league.MatchDayService
import services.league.PlayerScoreService
import services.league.PlayoffService
import services.league.RankingService
import services.league.SeasonService

val LeagueModule = module {
    // Repositories
    single<SeasonRepository> { SeasonRepositoryImpl(get(), get(), get()) }
    single<LeagueCategoryRepository> { LeagueCategoryRepositoryImpl(get(), get(), get()) }
    single<LeaguePlayerRepository> { LeaguePlayerRepositoryImpl(get(), get(), get(), get()) }
    single<MatchDayRepository> { MatchDayRepositoryImpl(get(), get(), get()) }
    single<DayGroupRepository> { DayGroupRepositoryImpl(get(), get(), get()) }
    single<DoublesMatchRepository> { DoublesMatchRepositoryImpl(get(), get(), get()) }
    single<RotationRepository> { RotationRepositoryImpl(get(), get(), get(), get()) }
    single<SeasonScheduleDefaultsRepository> { SeasonScheduleDefaultsRepositoryImpl(get(), get(), get()) }
    single<MatchdayScheduleOverridesRepository> { MatchdayScheduleOverridesRepositoryImpl(get(), get(), get()) }
    single<PlayerAvailabilityRepository> { PlayerAvailabilityRepositoryImpl(get(), get(), get(), get()) }
    single<AdjustmentRepository> { AdjustmentRepositoryImpl(get(), get(), get()) }
    single<LeaguePaymentRepository> { LeaguePaymentRepositoryImpl(get(), get(), get()) }

    // Services
    single { SeasonService(get(), get(), get()) }
    single { LeagueCategoryService(get(), get(), get(), get(), get()) }
    single { RankingService(get(), get(), get()) }
    single { MatchDayService(get(), get(), get()) }
    single { MasterScheduleService(get(), get(), get(), get(), get(), get()) }
    single { PlayoffService(get(), get(), get()) }
    single { AutoSchedulingService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PlayerScoreService(get(), get(), get()) }
    single { LeaguePaymentService(get(), get(), get()) }
}
