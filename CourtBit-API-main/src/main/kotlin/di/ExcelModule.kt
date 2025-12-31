package di

import com.incodap.services.excel.ExcelService
import org.koin.dsl.module

val ExcelModule = module {
    single { ExcelService() }
}
