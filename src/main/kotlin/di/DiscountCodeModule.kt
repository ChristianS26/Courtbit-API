package di

import com.incodap.repositories.discountcode.DiscountCodeRepositoryImpl
import org.koin.dsl.module
import repositories.discountcode.DiscountCodeRepository
import services.discountcode.DiscountCodeService

val DiscountCodeModule = module {

    single<DiscountCodeRepository> {
        DiscountCodeRepositoryImpl(
            get(),
            get(),
            get()
        )
    }

    single {
        DiscountCodeService(get())
    }
}
