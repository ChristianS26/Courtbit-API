package di

import com.incodap.repositories.ranking.RankingRepository
import org.koin.dsl.module
import repositories.ranking.PointsConfigRepository
import repositories.ranking.PointsConfigRepositoryImpl
import repositories.ranking.RankingRepositoryImpl
import repositories.ranking.RankingSeasonRepository
import repositories.ranking.RankingSeasonRepositoryImpl
import routing.ranking.PointsConfigRoutes
import routing.ranking.RankingRoutes
import services.ranking.PointsConfigService
import services.ranking.RankingSeasonService
import services.ranking.RankingService

val RankingModule = module {
    single<RankingRepository> { RankingRepositoryImpl(get(), get(), get()) }
    single { RankingService(get()) }

    single {
        RankingRoutes(
            service = get()
        )
    }

    // Points Config
    single<PointsConfigRepository> { PointsConfigRepositoryImpl(get(), get(), get()) }
    single { PointsConfigService(get(), get()) }
    single { PointsConfigRoutes(service = get()) }

    // Ranking Seasons
    single<RankingSeasonRepository> { RankingSeasonRepositoryImpl(get(), get(), get()) }
    single { RankingSeasonService(get()) }
}
