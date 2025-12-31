package di

import com.incodap.repositories.users.UserRepository
import com.incodap.repositories.users.UserRepositoryImpl
import org.koin.dsl.module
import services.auth.AuthService
import utils.JwtService

val AuthModule = module {

    single<UserRepository> {
        UserRepositoryImpl(get(), get(), get())
    }

    single { JwtService() }

    single {
        AuthService(get(), get())
    }
}
