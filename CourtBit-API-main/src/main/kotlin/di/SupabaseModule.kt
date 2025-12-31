package di

import com.incodap.config.SupabaseConfig
import org.koin.dsl.module

val SupabaseModule = module {
    single {
        SupabaseConfig(
            apiUrl = System.getenv("SUPABASE_API_URL")
                ?: error("❌ SUPABASE_API_URL no configurada"),
            apiKey = System.getenv("SUPABASE_API_KEY")
                ?: error("❌ SUPABASE_API_KEY no configurada")
        )
    }
}

