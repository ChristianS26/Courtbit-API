package di

import org.koin.dsl.module
import repositories.ShirtSizeRepository
import repositories.ShirtSizeRepositoryImpl

val ShirtSizeModule = module {
    single<ShirtSizeRepository> {
        ShirtSizeRepositoryImpl(get(), get(), get())
    }
}
