package di

import com.incodap.repositories.ranking.RankingRepository
import org.koin.dsl.module
import repositories.ranking.RankingRepositoryImpl
import routing.ranking.RankingRoutes
import services.ranking.RankingService

val RankingModule = module {
    single<RankingRepository> { RankingRepositoryImpl(get(), get(), get()) }
    single { RankingService(get()) }

    single {
        RankingRoutes(
            service = get()
        )
    }
}
