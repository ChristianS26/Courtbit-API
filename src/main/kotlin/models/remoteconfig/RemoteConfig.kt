package models.remoteconfig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteConfig(
    val id: String? = null,
    val platform: String,
    @SerialName("min_version")
    val minVersion: Int,
    @SerialName("latest_version")
    val latestVersion: Int? = null,
    @SerialName("force_update")
    val forceUpdate: Boolean,
    val message: String? = null,
)
