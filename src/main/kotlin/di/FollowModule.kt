package di

import org.koin.dsl.module
import repositories.follow.FollowRepository
import repositories.follow.FollowRepositoryImpl
import services.follow.FollowService

val FollowModule = module {
    single<FollowRepository> {
        FollowRepositoryImpl(
            client = get(),
            json = get(),
            config = get<com.incodap.config.SupabaseConfig>()
        )
    }

    single {
        FollowService(repository = get())
    }
}
