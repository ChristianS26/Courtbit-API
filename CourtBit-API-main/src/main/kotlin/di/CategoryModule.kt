package di

import org.koin.dsl.module
import repositories.category.CategoryRepository
import repositories.category.CategoryRepositoryImpl
import services.category.CategoryService

val CategoryModule = module {
    single<CategoryRepository> {
        CategoryRepositoryImpl(
            client = get(),
            config = get(),
            json = get()
        )
    }

    single { CategoryService(get()) }
}
