package di

import org.koin.dsl.module
import services.explore.ExploreService

val ExploreModule = module {
    single {
        ExploreService(
            tournamentRepository = get(),
            seasonRepository = get(),
            organizerRepository = get()
        )
    }
}
