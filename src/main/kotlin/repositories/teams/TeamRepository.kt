package com.incodap.repositories.teams

import com.incodap.models.teams.RpcRegistrationRowDto
import com.incodap.models.users.UserDto
import com.incodap.models.teams.SetTeamResultRequest
import com.incodap.models.teams.TeamResultStatusDto
import models.ranking.TeamWithResultStatusDto
import models.teams.PendingTournamentPlayerLinkResponse
import models.teams.Team
import models.teams.TeamRequest

interface TeamRepository {
    suspend fun findTeam(tournamentId: String, playerA: String, playerB: String): Team?
    suspend fun createTeam(request: TeamRequest): Boolean
    suspend fun findTeamById(teamId: String): Team?
    suspend fun updateTeamCategory(teamId: String, newCategoryId: Int): Boolean
    suspend fun updateTeamPaidStatus(teamId: String, paidField: String, value: Boolean): Boolean
    suspend fun findTeamsByTournament(tournamentId: String): List<Team>
    suspend fun findUsersByIds(userIds: List<String>): List<UserDto>
    suspend fun deleteTeam(teamId: String): Boolean
    suspend fun markPlayerAsPaid(tournamentId: String, playerUid: String, partnerUid: String): Boolean
    suspend fun markBothPlayersAsPaid(tournamentId: String, playerUid: String, partnerUid: String): Boolean
    suspend fun findByPlayerAndCategory(playerUid: String, tournamentId: String, categoryId: Int): Team?
    suspend fun hasTeamsForTournament(tournamentId: String): Boolean
    suspend fun hasTeamsForTournamentAndCategoryName(tournamentId: String, categoryName: String): Boolean
    suspend fun updateTeamRestriction(teamId: String, restriction: String): Boolean
    suspend fun payAndMarkManual(
        teamId: String,
        tournamentId: String,
        paidBy: String,
        method: String,
        adminUid: String,
        playerUid: String?
    ): Boolean

    suspend fun setTeamResult(teamId: String, req: SetTeamResultRequest): String
    suspend fun unsetTeamResult(teamId: String)
    suspend fun getTeamsWithResultStatus(tournamentId: String): List<TeamWithResultStatusDto>
    suspend fun getTeamResultStatus(teamId: String): TeamResultStatusDto?

    suspend fun getUserRegistrationsInTournament(userUid: String, tournamentId: String): List<RpcRegistrationRowDto>
    suspend fun getUserRegistrations(
        userUid: String,
        status: String? = null,     // "active" | "upcoming" | "past" | null
        limit: Int = 50,
        offset: Int = 0
    ): List<RpcRegistrationRowDto>

    // Manual player linking
    suspend fun findPendingTournamentLinks(email: String, phone: String?): List<PendingTournamentPlayerLinkResponse>
    suspend fun linkTournamentPlayerToUser(teamId: String, playerPosition: String, userUid: String): Boolean
}