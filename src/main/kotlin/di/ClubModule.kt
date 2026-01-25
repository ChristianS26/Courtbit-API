package di

import com.incodap.repositories.club.ClubRepository
import com.incodap.repositories.club.ClubRepositoryImpl
import com.incodap.services.club.ClubService
import org.koin.dsl.module

val ClubModule = module {
    single<ClubRepository> { ClubRepositoryImpl(get(), get(), get()) }
    single { ClubService(get()) }
}
