package services.remoteconfig

import models.remoteconfig.RemoteConfig
import repositories.remoteconfig.RemoteConfigRepository

class RemoteConfigService(
    private val repository: RemoteConfigRepository
) {
    suspend fun getByPlatform(platform: String): RemoteConfig? {
        return repository.getByPlatform(platform)
    }
}
