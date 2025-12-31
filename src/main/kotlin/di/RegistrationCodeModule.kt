package di

import com.incodap.repositories.registrationcode.RegistrationCodeRepositoryImpl
import org.koin.dsl.module
import repositories.registrationcode.RegistrationCodeRepository
import services.registrationcode.RegistrationCodeService

val RegistrationCodeModule = module {

    single<RegistrationCodeRepository> {
        RegistrationCodeRepositoryImpl(
            get(),
            get(),
            get()
        )
    }

    single {
        RegistrationCodeService(get())
    }
}
