package di

import org.koin.dsl.module
import repositories.organizer.OrganizerRepository
import repositories.organizer.OrganizerRepositoryImpl
import services.organizer.OrganizerService

val OrganizerModule = module {
    // Repository binding
    single<OrganizerRepository> {
        OrganizerRepositoryImpl(
            client = get(),  // HttpClient from CoreModule
            json = get(),    // Json from CoreModule
            config = get()   // SupabaseConfig from SupabaseModule
        )
    }

    // Service binding
    single {
        OrganizerService(
            repository = get()  // OrganizerRepository
        )
    }
}
