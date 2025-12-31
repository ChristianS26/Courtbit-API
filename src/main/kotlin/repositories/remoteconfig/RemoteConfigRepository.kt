package repositories.remoteconfig

import models.remoteconfig.RemoteConfig

interface RemoteConfigRepository {
    suspend fun getByPlatform(platform: String): RemoteConfig?
}
