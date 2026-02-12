package di

import com.incodap.repositories.payments.PaymentRepository
import com.incodap.repositories.payments.PaymentRepositoryImpl
import com.incodap.services.payments.PaymentService
import com.incodap.services.payments.StripeConnectService
import com.incodap.services.payments.StripeWebhookService
import org.koin.dsl.module
import routing.payments.PaymentRoutes

val PaymentModule = module {

    single<PaymentRepository> {
        PaymentRepositoryImpl(
            client = get(),
            json = get(),
            config = get()
        )
    }

    single {
        PaymentService(
            teamRepository = get(), paymentRepository = get(),
            excelService = get(),
            emailService = get(),
            tournamentRepository = get(),
            categoryRepository = get(),
            userRepository = get(),
            organizerRepository = get(),
            registrationCodeRepository = get(),
            discountCodeRepository = get()
        )
    }

    single {
        val stripeWebhookSecret = System.getenv("STRIPE_WEBHOOK_SECRET")
            ?: error("❌ STRIPE_WEBHOOK_SECRET no configurado")
        StripeWebhookService(
            endpointSecret = stripeWebhookSecret,
            paymentRepository = get(),
            emailService = get(),
            tournamentRepository = get(),
            categoryRepository = get()
        )
    }

    single {
        StripeConnectService(
            httpClient = get()
        )
    }

    single {
        PaymentRoutes(
            paymentService = get(),
            stripeWebhookService = get(),
            stripeConnectService = get(),
            organizerRepository = get()
        )
    }
}
