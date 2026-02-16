package di

import org.koin.dsl.module
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository
import repositories.bracket.BracketRepositoryImpl
import services.bracket.BracketGenerationService
import services.bracket.BracketScoringService
import services.bracket.BracketService
import services.bracket.BracketStandingsService

val BracketModule = module {
    single<BracketRepository> { BracketRepositoryImpl(get(), get(), get()) }
    single { BracketAuditRepository(get(), get()) }
    single { BracketStandingsService(get(), get()) }  // repository + json
    single { BracketService(get(), get(), get()) }  // repository + json + audit
    single { BracketScoringService(get(), get(), get(), get()) }  // repository + json + audit + standings
    single { BracketGenerationService(get(), get(), get(), get()) }  // repository + json + audit + standings
}
