package com.incodap.routing

import com.incodap.routing.auth.authRoutes
import com.incodap.routing.category.categoryRoutes
import com.incodap.routing.cloudinary.cloudinaryRoutes
import com.incodap.routing.cloudinary.uploadProfilePhotoRoute
import com.incodap.routing.registrationcode.registrationCodeRoutes
import com.incodap.routing.teams.teamRoutes
import com.incodap.routing.tournament.tournamentRoutes
import com.incodap.services.excel.ExcelService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import repositories.league.DayGroupRepository
import repositories.league.DoublesMatchRepository
import repositories.league.LeagueCategoryRepository
import repositories.league.LeaguePlayerRepository
import repositories.league.MatchDayRepository
import repositories.league.RotationRepository
import repositories.league.SeasonRepository
import repositories.organizer.OrganizerRepository
import routing.auth.profileRoute
import routing.draw.drawRoutes
import routing.league.dayGroupRoutes
import routing.league.doublesMatchRoutes
import routing.league.leagueCategoryRoutes
import routing.league.leaguePlayerRoutes
import routing.league.leagueRankingRoutes
import routing.league.matchDayOptimizedRoutes
import routing.league.matchDayRoutes
import routing.league.rotationRoutes
import routing.league.seasonRoutes
import routing.notifications.pushRoutes
import routing.organizer.organizerRoutes
import routing.payments.PaymentRoutes
import routing.ranking.RankingRoutes
import routing.remoteconfig.remoteConfigRoutes
import services.auth.AuthService
import services.email.EmailService
import services.league.LeagueCategoryService
import services.league.MatchDayService
import services.league.RankingService
import services.league.SeasonService
import services.organizer.OrganizerService
import services.registrationcode.RegistrationCodeService
import services.remoteconfig.RemoteConfigService
import services.teams.TeamService

fun Application.configureRouting() {
    var rootRoute: Route? = null

    routing {
        rootRoute = this

        // Root health check endpoint
        get("/") {
            call.respondText("CourtBit API is running ✅")
        }

        route("/api") {
            // Profile
            profileRoute(get(), get())

            // Auth
            authRoutes(get<AuthService>(), get<EmailService>())

            // Payments & Stripe
            get<PaymentRoutes>().register(this)

            // Cloudinary
            cloudinaryRoutes()
            uploadProfilePhotoRoute(get())

            // Equipos
            teamRoutes(get<TeamService>(), get<EmailService>(), get<ExcelService>())

            // Códigos de inscripción
            registrationCodeRoutes(get<RegistrationCodeService>(), get<EmailService>(), get<ExcelService>(), get())

            // Torneos
            tournamentRoutes(get(), get(), get())

            // Draws
            drawRoutes(get())

            // Ranking
            get<RankingRoutes>().register(this)

            // Remote Config
            remoteConfigRoutes(get<RemoteConfigService>())

            // Categorías
            categoryRoutes(get())

            // Organizers
            organizerRoutes(get<OrganizerService>())

            // Push notifications
            pushRoutes(get())

            // League system
            seasonRoutes(get<SeasonService>(), get<SeasonRepository>(), get<OrganizerRepository>())
            leagueCategoryRoutes(get<LeagueCategoryService>(), get<LeagueCategoryRepository>())
            leaguePlayerRoutes(get<LeaguePlayerRepository>())
            matchDayRoutes(get<MatchDayRepository>())
            matchDayOptimizedRoutes(get<MatchDayService>())  // Optimized endpoint
            dayGroupRoutes(get<DayGroupRepository>())
            rotationRoutes(get<RotationRepository>())
            doublesMatchRoutes(get<DoublesMatchRepository>())
            leagueRankingRoutes(get<RankingService>())
        }
    }

    // Log del árbol de rutas al iniciar (útil para confirmar que /api/config/remote-config existe)
    environment.monitor.subscribe(ApplicationStarted) {
        rootRoute?.printTree(this)
    }
}

private fun Route.printTree(app: Application, prefix: String = "") {
    app.log.info("$prefix$selector")
    children.forEach { it.printTree(app, "$prefix  ") }
}
