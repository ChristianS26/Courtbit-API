package repositories.follow

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class FollowRepositoryImpl(
    private val client: HttpClient,
    private val json: Json,
    private val config: SupabaseConfig
) : FollowRepository {

    private val apiUrl = config.apiUrl
    private val apiKey = config.apiKey

    override suspend fun follow(userId: String, organizerId: String): Boolean {
        return try {
            @Serializable
            data class FollowPayload(
                @SerialName("user_id") val userId: String,
                @SerialName("organizer_id") val organizerId: String
            )

            val response = client.post("$apiUrl/organizer_followers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(FollowPayload.serializer(), FollowPayload(userId, organizerId)))
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun unfollow(userId: String, organizerId: String): Boolean {
        return try {
            val response = client.delete("$apiUrl/organizer_followers") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                parameter("user_id", "eq.$userId")
                parameter("organizer_id", "eq.$organizerId")
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getFollowerCount(organizerId: String): Long {
        return try {
            @Serializable
            data class RpcPayload(@SerialName("p_organizer_id") val organizerId: String)

            val response = client.post("$apiUrl/rpc/get_organizer_follower_count") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RpcPayload.serializer(), RpcPayload(organizerId)))
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText().trim()
                body.toLongOrNull() ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun isFollowing(userId: String, organizerId: String): Boolean {
        return try {
            @Serializable
            data class RpcPayload(
                @SerialName("p_user_id") val userId: String,
                @SerialName("p_organizer_id") val organizerId: String
            )

            val response = client.post("$apiUrl/rpc/is_following_organizer") {
                header("apikey", apiKey)
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RpcPayload.serializer(), RpcPayload(userId, organizerId)))
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText().trim()
                body == "true"
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
