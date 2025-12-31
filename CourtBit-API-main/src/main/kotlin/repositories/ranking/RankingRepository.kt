package com.incodap.repositories.ranking

import models.ranking.AddRankingEventRequest
import models.ranking.PlayerProfileResponse
import models.ranking.Ranking
import models.ranking.RankingItemResponse

interface RankingRepository {
    suspend fun addRankingEvent(request: AddRankingEventRequest) : String
    suspend fun getRanking(season: String?, categoryId: Int?): List<RankingItemResponse>
    suspend fun getRankingByUser(userId: String, season: String?): List<Ranking>
    suspend fun getPlayerProfile(userId: String, categoryId: Int): PlayerProfileResponse
    suspend fun getRankingForMultipleUsersAndCategories(userIds: List<String>, categoryIds: List<Int>, season: String?): List<Ranking>
}