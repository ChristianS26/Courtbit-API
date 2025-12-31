package repositories.remoteconfig

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import models.remoteconfig.RemoteConfig

class RemoteConfigRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : RemoteConfigRepository {

    override suspend fun getByPlatform(platform: String): RemoteConfig? {
        val response = client.get("${config.apiUrl}/remote_config") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("select", "*")
            parameter("platform", "eq.$platform")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(ListSerializer(RemoteConfig.serializer()), response.bodyAsText())
                .firstOrNull()
        } else null
    }

}
