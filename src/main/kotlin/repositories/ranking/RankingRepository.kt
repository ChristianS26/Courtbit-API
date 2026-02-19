package com.incodap.repositories.ranking

import models.ranking.AddRankingEventRequest
import models.ranking.AssignedRankingEventResponse
import models.ranking.BatchRankingRpcResponse
import models.ranking.PlayerProfileResponse
import models.ranking.Ranking
import models.ranking.RankingItemResponse

interface RankingRepository {
    suspend fun addRankingEvent(request: AddRankingEventRequest) : String
    suspend fun batchAddRankingEvents(tournamentId: String, categoryId: Int, season: String, entries: List<Map<String, Any?>>, tournamentName: String? = null): BatchRankingRpcResponse
    suspend fun getTournamentName(tournamentId: String): String?
    suspend fun getRanking(season: String?, categoryId: Int?, organizerId: String? = null): List<RankingItemResponse>
    suspend fun getRankingByUser(userId: String, season: String?): List<Ranking>
    suspend fun getPlayerProfile(userId: String, categoryId: Int, season: String? = null): PlayerProfileResponse
    suspend fun getRankingForMultipleUsersAndCategories(userIds: List<String>, categoryIds: List<Int>, season: String?): List<Ranking>
    suspend fun getRankingByEmails(emails: List<String>, categoryIds: List<Int>, season: String?): List<Ranking>
    suspend fun getRankingByPhones(phones: List<String>, categoryIds: List<Int>, season: String?): List<Ranking>
    suspend fun checkExistingEvents(tournamentId: String, categoryId: Int): Boolean
    suspend fun getAssignedEvents(tournamentId: String, categoryId: Int): List<AssignedRankingEventResponse>
    suspend fun revertRankingEvents(tournamentId: String, categoryId: Int): Int
}