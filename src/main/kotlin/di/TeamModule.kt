package di

import com.incodap.repositories.registrationcode.RegistrationCodeRepositoryImpl
import com.incodap.repositories.teams.TeamRepository
import repositories.teams.TeamRepositoryImpl
import services.teams.TeamService
import org.koin.dsl.module

val TeamModule = module {

    single<TeamRepository> { TeamRepositoryImpl(get(),get(),get()) }
    single { RegistrationCodeRepositoryImpl(get(),get(),get()) }
    single {
        TeamService(
            teamRepository = get(),
            registrationCodeRepository = get(),
            rankingService = get(),
            categoryRepository = get()
        )
    }
}
