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
import repositories.league.AdjustmentRepository
import repositories.league.DayGroupRepository
import repositories.league.SeasonCourtRepository
import repositories.league.DoublesMatchRepository
import repositories.league.LeagueCategoryRepository
import repositories.league.LeaguePlayerRepository
import repositories.league.MatchDayRepository
import repositories.league.MatchdayScheduleOverridesRepository
import repositories.league.PlayerAvailabilityRepository
import repositories.league.RotationRepository
import repositories.league.SeasonRepository
import repositories.league.SeasonScheduleDefaultsRepository
import com.incodap.repositories.ranking.RankingRepository
import com.incodap.repositories.teams.TeamRepository
import routing.auth.profileRoute
import routing.draw.drawRoutes
import routing.league.adjustmentRoutes
import routing.league.courtRoutes
import routing.league.dayGroupRoutes
import routing.league.doublesMatchRoutes
import routing.league.leagueCategoryRoutes
import routing.league.leaguePaymentRoutes
import routing.league.leaguePlayerRoutes
import routing.league.playerLinkRoutes
import routing.league.leagueRankingRoutes
import routing.league.matchDayOptimizedRoutes
import routing.league.matchDayRoutes
import routing.league.playerAvailabilityRoutes
import routing.league.playoffRoutes
import routing.league.rotationRoutes
import routing.league.scheduleRoutes
import routing.league.seasonRoutes
import routing.notifications.pushRoutes
import routing.organization.organizationTeamRoutes
import routing.organizer.organizerRoutes
import routing.payments.PaymentRoutes
import routing.ranking.PointsConfigRoutes
import routing.ranking.RankingRoutes
import routing.remoteconfig.remoteConfigRoutes
import routing.shirtSizeRoutes
import routing.bracket.bracketRoutes
import com.incodap.routing.club.clubRoutes
import com.incodap.routing.city.cityRoutes
import com.incodap.routing.padelclub.padelClubRoutes
import com.incodap.routing.discountcode.discountCodeRoutes
import com.incodap.services.club.ClubService
import services.city.CityService
import services.padelclub.PadelClubService
import services.auth.AuthService
import services.email.EmailService
import services.league.AutoSchedulingService
import services.league.LeagueCategoryService
import services.league.LeaguePaymentService
import services.league.MasterScheduleService
import services.league.MatchDayService
import services.league.PlayerScoreService
import services.league.PlayoffService
import services.league.RankingService
import services.league.SeasonService
import services.organization.OrganizationTeamService
import services.organizer.OrganizerService
import services.registrationcode.RegistrationCodeService
import services.discountcode.DiscountCodeService
import services.remoteconfig.RemoteConfigService
import services.teams.TeamService

fun Application.configureRouting() {
    routing {

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
            registrationCodeRoutes(get<RegistrationCodeService>(), get<EmailService>(), get<ExcelService>())

            // Códigos de descuento
            discountCodeRoutes(get<DiscountCodeService>())

            // Torneos
            tournamentRoutes(get(), get(), get<ClubService>())

            // Brackets (tournament brackets system)
            bracketRoutes(get())

            // Clubs (venue management)
            clubRoutes(get<ClubService>())

            // Cities catalog
            cityRoutes(get<CityService>())

            // Padel Clubs catalog
            padelClubRoutes(get<PadelClubService>())

            // Draws
            drawRoutes(get())

            // Ranking
            get<RankingRoutes>().register(this)

            // Points Config
            get<PointsConfigRoutes>().register(this)

            // Remote Config
            remoteConfigRoutes(get<RemoteConfigService>())

            // Categorías
            categoryRoutes(get())

            // Organizers
            organizerRoutes(get<OrganizerService>())

            // Organization Team (members & invitations)
            organizationTeamRoutes(get<OrganizationTeamService>())

            // Push notifications
            pushRoutes(get())

            // Shirt sizes catalog (public)
            shirtSizeRoutes()

            // League system
            courtRoutes(get<SeasonCourtRepository>())
            seasonRoutes(get<SeasonService>(), get<SeasonRepository>())
            leagueCategoryRoutes(get<LeagueCategoryService>(), get<LeagueCategoryRepository>())
            leaguePlayerRoutes(get<LeaguePlayerRepository>())
            playerLinkRoutes(get<LeaguePlayerRepository>(), get<TeamRepository>(), get(), get<RankingRepository>())
            leaguePaymentRoutes(get<LeaguePaymentService>(), get<SeasonRepository>())
            matchDayRoutes(get<MatchDayRepository>())
            matchDayOptimizedRoutes(get<MatchDayService>())  // Optimized endpoint
            dayGroupRoutes(get<DayGroupRepository>())
            rotationRoutes(get<RotationRepository>())
            doublesMatchRoutes(get<DoublesMatchRepository>(), get<PlayerScoreService>())
            leagueRankingRoutes(get<RankingService>())
            playoffRoutes(get<PlayoffService>(), get())
            playerAvailabilityRoutes(get<PlayerAvailabilityRepository>())
            adjustmentRoutes(get<AdjustmentRepository>())
            scheduleRoutes(
                get<SeasonScheduleDefaultsRepository>(),
                get<MatchdayScheduleOverridesRepository>(),
                get<DayGroupRepository>(),
                get<MasterScheduleService>(),
                get<SeasonRepository>(),
                get<AutoSchedulingService>(),
                get<SeasonCourtRepository>()
            )
        }
    }

}
