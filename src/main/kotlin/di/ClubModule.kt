package di

import com.incodap.repositories.club.ClubRepository
import com.incodap.repositories.club.ClubRepositoryImpl
import com.incodap.services.club.ClubService
import org.koin.dsl.module

val ClubModule = module {
    single<ClubRepository> {
        ClubRepositoryImpl(
            client = get(),  // HttpClient from CoreModule
            config = get()   // SupabaseConfig from SupabaseModule
        )
    }
    single { ClubService(get()) }
}
