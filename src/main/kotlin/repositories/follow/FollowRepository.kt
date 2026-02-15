package repositories.follow

interface FollowRepository {
    suspend fun follow(userId: String, organizerId: String): Boolean
    suspend fun unfollow(userId: String, organizerId: String): Boolean
    suspend fun getFollowerCount(organizerId: String): Long
    suspend fun isFollowing(userId: String, organizerId: String): Boolean
}
