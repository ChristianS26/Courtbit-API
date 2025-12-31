package di

import org.koin.dsl.module
import repositories.email.EmailRepository
import repositories.email.EmailRepositoryImpl
import services.email.EmailService

val EmailModule = module {

    single<EmailRepository> {
        EmailRepositoryImpl(
            client = get(),
            config = get()
        )
    }

    single {
        EmailService(
            repository = get()
        )
    }
}
