package com.incodap

import com.incodap.plugins.configureCors
import com.incodap.plugins.configureSecurity
import com.incodap.plugins.configureSerialization
import com.incodap.routing.configureRouting
import di.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.ktor.plugin.Koin

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(Koin) {
        modules(
            CoreModule,
            SupabaseModule,
            ResendModule,
            PaymentModule,
            TournamentModule,
            TeamModule,
            AuthModule,
            EmailModule,
            CloudinaryModule,
            RegistrationCodeModule,
            ExcelModule,
            RemoteConfigModule,
            DrawModule,
            RankingModule,
            CategoryModule,
            NotificationsModule,
            OrganizerModule,
            OrganizationTeamModule,
            LeagueModule,
            ShirtSizeModule,
            BracketModule,
            CityPadelClubModule,
            DiscountCodeModule,
            FollowModule,
            ExploreModule,
        )
    }

    configureSecurity()
    configureSerialization()
    configureCors()
    configureRouting()
}
