package di

import org.koin.dsl.module
import repositories.organization.OrganizationTeamRepository
import repositories.organization.OrganizationTeamRepositoryImpl
import services.organization.OrganizationTeamService

val OrganizationTeamModule = module {
    // Repository binding
    single<OrganizationTeamRepository> {
        OrganizationTeamRepositoryImpl(
            client = get(),  // HttpClient from CoreModule
            json = get(),    // Json from CoreModule
            config = get()   // SupabaseConfig from SupabaseModule
        )
    }

    // Service binding
    single {
        OrganizationTeamService(
            repository = get(),         // OrganizationTeamRepository
            organizerRepository = get(), // OrganizerRepository for ownership checks
            userRepository = get()      // UserRepository for email lookup
        )
    }
}
