package di

import com.incodap.repositories.ranking.RankingRepository
import org.koin.dsl.module
import repositories.ranking.PointsConfigRepository
import repositories.ranking.PointsConfigRepositoryImpl
import repositories.ranking.RankingRepositoryImpl
import routing.ranking.PointsConfigRoutes
import routing.ranking.RankingRoutes
import services.ranking.PointsConfigService
import services.ranking.RankingService

val RankingModule = module {
    single<RankingRepository> { RankingRepositoryImpl(get(), get(), get()) }
    single { RankingService(get(), get()) }

    single {
        RankingRoutes(
            service = get()
        )
    }

    // Points Config
    single<PointsConfigRepository> { PointsConfigRepositoryImpl(get(), get(), get()) }
    single { PointsConfigService(get(), get()) }
    single { PointsConfigRoutes(service = get()) }
}
