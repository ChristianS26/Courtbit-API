package di

import org.koin.dsl.module
import repositories.city.CityRepository
import repositories.city.CityRepositoryImpl
import repositories.padelclub.PadelClubRepository
import repositories.padelclub.PadelClubRepositoryImpl
import services.city.CityService
import services.padelclub.PadelClubService

val CityPadelClubModule = module {
    // City
    single<CityRepository> {
        CityRepositoryImpl(
            client = get(),
            config = get(),
            json = get()
        )
    }

    single { CityService(get()) }

    // Padel Club
    single<PadelClubRepository> {
        PadelClubRepositoryImpl(
            client = get(),
            config = get(),
            json = get()
        )
    }

    single { PadelClubService(get()) }
}
