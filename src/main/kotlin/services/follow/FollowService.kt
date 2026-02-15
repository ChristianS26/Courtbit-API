package services.follow

import repositories.follow.FollowRepository

class FollowService(
    private val repository: FollowRepository
) {
    suspend fun follow(userId: String, organizerId: String): Boolean {
        return repository.follow(userId, organizerId)
    }

    suspend fun unfollow(userId: String, organizerId: String): Boolean {
        return repository.unfollow(userId, organizerId)
    }

    suspend fun getFollowerCount(organizerId: String): Long {
        return repository.getFollowerCount(organizerId)
    }

    suspend fun isFollowing(userId: String, organizerId: String): Boolean {
        return repository.isFollowing(userId, organizerId)
    }

    suspend fun getFollowedOrganizerIds(userId: String): List<String> {
        return repository.getFollowedOrganizerIds(userId)
    }
}
