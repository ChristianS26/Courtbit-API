package services.teams

import com.incodap.models.teams.RegisterWithCodeRequest
import com.incodap.models.teams.RpcRegistrationRowDto
import com.incodap.models.teams.SetTeamResultRequest
import com.incodap.models.teams.TeamResultStatusDto
import com.incodap.models.users.UserDto
import com.incodap.repositories.teams.TeamRepository
import models.category.CategoryResponseDto
import models.ranking.TeamWithResultStatusDto
import models.registrationcode.RegisterTeamResult
import models.teams.MarkPaymentRequest
import models.teams.RegisterTeamRequest
import models.teams.RegisteredInTournamentResponse
import models.teams.ReplacePlayerRequest
import models.teams.RegistrationItemDto
import models.teams.RegistrationTournamentDto
import models.teams.Team
import models.teams.TeamGroupByCategoryFullResponse
import models.teams.TeamPlayerDto
import models.teams.TeamRequest
import models.teams.TeamWithPlayerDto
import models.teams.createManualPlayerDto
import models.teams.toTeamPlayerDto
import repositories.category.CategoryRepository
import repositories.registrationcode.RegistrationCodeRepository
import services.ranking.RankingService

class TeamService(
    private val teamRepository: TeamRepository,
    private val registrationCodeRepository: RegistrationCodeRepository,
    private val rankingService: RankingService,
    private val categoryRepository: CategoryRepository,
) {
    enum class PlayerType(val fieldName: String) {
        PLAYER_A("player_a_paid"),
        PLAYER_B("player_b_paid");

        companion object {
            fun fromString(value: String): PlayerType? = when (value) {
                "player_a" -> PLAYER_A
                "player_b" -> PLAYER_B
                else -> null
            }
        }
    }

    suspend fun registerTeam(req: RegisterTeamRequest): Boolean {
        // For teams with linked users, check if team already exists
        if (req.playerUid != null && req.partnerUid != null) {
            val existing = teamRepository.findTeam(req.tournamentId, req.playerUid, req.partnerUid)
            if (existing != null) {
                return updateExistingTeam(existing, req)
            }
        }
        // Create new team (may have manual players)
        return createNewTeam(req)
    }

    suspend fun teamExists(tournamentId: String, playerA: String, playerB: String): Team? {
        return teamRepository.findTeam(tournamentId, playerA, playerB)
    }

    suspend fun getTeamsGroupedByCategoryWithPlayerInfo(
        tournamentId: String
    ): List<TeamGroupByCategoryFullResponse> {
        val teams = teamRepository.findTeamsByTournament(tournamentId)

        // Fetch tournament categories with maxTeams (even if no teams yet)
        val tournamentCategories = categoryRepository.getCategoriesByTournamentId(tournamentId)
        val maxTeamsByCategoryId = tournamentCategories.associate { it.id.toIntOrNull() to it.maxTeams }
        val colorByCategoryId = tournamentCategories.associate { it.id.toIntOrNull() to it.color }

        if (teams.isEmpty()) {
            // Return categories with maxTeams even when no teams registered
            return tournamentCategories.map { cat ->
                TeamGroupByCategoryFullResponse(
                    categoryName = cat.name,
                    teams = emptyList(),
                    maxTeams = cat.maxTeams,
                    color = cat.color
                )
            }
        }

        // Only fetch users for linked players (non-null UIDs)
        val allPlayerUids = teams.flatMap { listOfNotNull(it.playerAUid, it.playerBUid) }.distinct()
        val allCategoryIds = teams.map { it.category.id }.distinct()
        val userMap = teamRepository.findUsersByIds(allPlayerUids).associateBy { it.uid }

        val allRankings = if (allPlayerUids.isNotEmpty()) {
            rankingService.getRankingForMultipleUsersAndCategories(
                allPlayerUids, allCategoryIds, "2025"
            )
        } else emptyList()
        val pointsByUserAndCategory =
            allRankings.associate { (it.userId to it.category.id) to it.totalPoints }

        val statusList: List<TeamWithResultStatusDto> =
            teamRepository.getTeamsWithResultStatus(tournamentId)
        val teamIdsWithResult: Set<String> =
            statusList.asSequence().filter { it.hasResult }.map { it.id }.toSet()

        val teamsByCategoryId: Map<Int, List<Team>> =
            teams.groupBy { it.category.id }

        val categoriesOrdered = teams
            .map { it.category }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.name }))

        val categoriesWithTeams = categoriesOrdered.map { cat ->
            val teamsInCategory = teamsByCategoryId[cat.id].orEmpty()

            val sortedTeams = teamsInCategory
                .map { team ->
                    val aPts = team.playerAUid?.let { pointsByUserAndCategory[(it to team.category.id)] } ?: 0
                    val bPts = team.playerBUid?.let { pointsByUserAndCategory[(it to team.category.id)] } ?: 0
                    Triple(team, aPts, bPts)
                }
                .sortedByDescending { it.second + it.third }

            TeamGroupByCategoryFullResponse(
                categoryName = cat.name,
                teams = sortedTeams.map { (team, aPts, bPts) ->
                    TeamWithPlayerDto(
                        id = team.id,
                        category = team.category,
                        playerA = resolvePlayer(team.playerAUid, team.playerAName, team.playerAEmail, team.playerAPhone, userMap),
                        playerB = resolvePlayer(team.playerBUid, team.playerBName, team.playerBEmail, team.playerBPhone, userMap),
                        playerAPoints = aPts,
                        playerAPaid = team.playerAPaid,
                        playerBPoints = bPts,
                        playerBPaid = team.playerBPaid,
                        hasResult = teamIdsWithResult.contains(team.id),
                        restriction = team.restriction
                    )
                },
                maxTeams = maxTeamsByCategoryId[cat.id],
                color = colorByCategoryId[cat.id]
            )
        }

        // Include tournament categories that have no teams yet
        val categoryIdsWithTeams = categoriesOrdered.map { it.id }.toSet()
        val emptyCategories = tournamentCategories
            .filter { it.id.toIntOrNull() !in categoryIdsWithTeams }
            .map { cat ->
                TeamGroupByCategoryFullResponse(
                    categoryName = cat.name,
                    teams = emptyList(),
                    maxTeams = cat.maxTeams,
                    color = cat.color
                )
            }

        return categoriesWithTeams + emptyCategories
    }

    /**
     * Resolves a player to a TeamPlayerDto.
     * If uid is provided, looks up the user in the map.
     * If uid is null but name is provided, creates a manual player entry.
     */
    private fun resolvePlayer(
        uid: String?,
        manualName: String?,
        manualEmail: String?,
        manualPhone: String?,
        userMap: Map<String, UserDto>
    ): TeamPlayerDto {
        return when {
            uid != null -> userMap[uid]?.toTeamPlayerDto()
                ?: error("No user found for uid: $uid")
            manualName != null -> createManualPlayerDto(manualName, manualEmail, manualPhone)
            else -> error("Player has neither uid nor name")
        }
    }

    fun mapTeamToPlayerDtoWithPoints(
        team: Team,
        userMap: Map<String, UserDto>,
        pointsByUserAndCategory: Map<Pair<String, Int>, Int>
    ): TeamWithPlayerDto {
        val categoryId = team.category.id

        val playerA = resolvePlayer(team.playerAUid, team.playerAName, team.playerAEmail, team.playerAPhone, userMap)
        val playerB = resolvePlayer(team.playerBUid, team.playerBName, team.playerBEmail, team.playerBPhone, userMap)

        return TeamWithPlayerDto(
            id = team.id,
            category = team.category,
            playerA = playerA,
            playerB = playerB,
            playerAPoints = team.playerAUid?.let { pointsByUserAndCategory[it to categoryId] } ?: 0,
            playerAPaid = team.playerAPaid,
            playerBPoints = team.playerBUid?.let { pointsByUserAndCategory[it to categoryId] } ?: 0,
            playerBPaid = team.playerBPaid
        )
    }

    suspend fun markPaymentManual(req: MarkPaymentRequest, adminUid: String): Boolean {
        val fieldName = when (req.paidBy) {
            "player_a" -> "player_a_paid"
            "player_b" -> "player_b_paid"
            else -> return false
        }

        if (!req.paid) {
            return teamRepository.updateTeamPaidStatus(
                teamId = req.teamId,
                paidField = fieldName,
                value = false
            )
        }

        // For manual players (no playerUid), use simple direct update
        // The RPC function requires playerUid for audit/payment tracking
        if (req.playerUid.isNullOrBlank()) {
            return teamRepository.updateTeamPaidStatus(
                teamId = req.teamId,
                paidField = fieldName,
                value = true
            )
        }

        val ok = teamRepository.payAndMarkManual(
            teamId = req.teamId,
            tournamentId = req.tournamentId,
            paidBy = req.paidBy,
            method = req.method!!,
            adminUid = adminUid,
            playerUid = req.playerUid
        )
        return ok
    }

    suspend fun deleteTeam(teamId: String): Boolean {
        return teamRepository.deleteTeam(teamId)
    }

    suspend fun registerTeamWithCode(request: RegisterWithCodeRequest): RegisterTeamResult {
        return try {
            val registrationCode = registrationCodeRepository.getValidCode(request.code)
            if (registrationCode == null) {
                return RegisterTeamResult.InvalidCode
            }

            val existingTeam = teamRepository.findByPlayerAndCategory(
                playerUid = request.playerUid,
                tournamentId = request.tournamentId,
                categoryId = request.categoryId ?: 0
            )

            if (existingTeam != null) {
                val alreadyPaid = when (request.playerUid) {
                    existingTeam.playerAUid -> existingTeam.playerAPaid
                    existingTeam.playerBUid -> existingTeam.playerBPaid
                    else -> false
                }

                if (alreadyPaid) {
                    return RegisterTeamResult.AlreadyRegistered
                }

                val success =
                    registrationCodeRepository.markCodeAsUsed(request.code, request.email, request.tournamentId)
                if (!success) {
                    return RegisterTeamResult.InvalidCode
                }

                val fieldToUpdate = when (request.playerUid) {
                    existingTeam.playerAUid -> "player_a_paid"
                    existingTeam.playerBUid -> "player_b_paid"
                    else -> return RegisterTeamResult.InvalidCode
                }

                if (!request.restriction.isNullOrBlank()) {
                    val newRestriction = buildUpdatedRestriction(
                        existingTeam.restriction,
                        request.playerName,
                        request.restriction
                    )
                    teamRepository.updateTeamRestriction(existingTeam.id, newRestriction)
                }

                teamRepository.updateTeamPaidStatus(existingTeam.id, fieldToUpdate, true)
                return RegisterTeamResult.Updated
            }

            val marked = registrationCodeRepository.markCodeAsUsed(request.code, request.email, request.tournamentId)
            if (!marked) {
                return RegisterTeamResult.InvalidCode
            }

            val created = createTeamWithCode(request)
            if (!created) {
                return RegisterTeamResult.InvalidCode
            }

            return RegisterTeamResult.Created

        } catch (e: Exception) {
            RegisterTeamResult.InvalidCode
        }
    }

    private fun buildUpdatedRestriction(existing: String?, playerName: String?, restriction: String): String {
        val entry = "$playerName: $restriction"
        return if (existing.isNullOrBlank()) entry else "$existing / $entry"
    }

    private suspend fun createNewTeam(request: RegisterTeamRequest): Boolean {
        return teamRepository.createTeam(
            TeamRequest(
                tournamentId = request.tournamentId,
                playerAUid = request.playerUid,
                playerBUid = request.partnerUid,
                categoryId = request.categoryId,
                playerAPaid = false,
                playerBPaid = false,
                // Manual player fields
                playerAName = request.playerName,
                playerAEmail = request.playerEmail,
                playerAPhone = request.playerPhone,
                playerBName = request.partnerName,
                playerBEmail = request.partnerEmail,
                playerBPhone = request.partnerPhone
            )
        )
    }

    private suspend fun updateExistingTeam(existing: Team, req: RegisterTeamRequest): Boolean {
        val paidField =
            if (existing.playerAUid == req.playerUid) PlayerType.PLAYER_A.fieldName else PlayerType.PLAYER_B.fieldName
        return teamRepository.updateTeamPaidStatus(existing.id, paidField, true)
    }

    private suspend fun createTeamWithCode(request: RegisterWithCodeRequest): Boolean {
        val restrictionText = if (!request.restriction.isNullOrBlank()) {
            "${request.playerName}: ${request.restriction}"
        } else {
            ""
        }
        return teamRepository.createTeam(
            TeamRequest(
                playerAUid = request.playerUid,
                playerBUid = request.partnerUid,
                tournamentId = request.tournamentId,
                categoryId = request.categoryId ?: 0,
                playerAPaid = true,
                playerBPaid = false,
                restriction = restrictionText,
            )
        )
    }

    suspend fun getTeamWithFullPlayerInfo(teamId: String): TeamWithPlayerDto? {
        val team = teamRepository.findTeamById(teamId) ?: return null

        // Only fetch users for linked players (non-null UIDs)
        val userUids = listOfNotNull(team.playerAUid, team.playerBUid)
        val users = teamRepository.findUsersByIds(userUids).associateBy { it.uid }

        val points = if (userUids.isNotEmpty()) {
            rankingService.getRankingForMultipleUsersAndCategories(
                userUids,
                listOf(team.category.id),
                "2025"
            ).associateBy(
                { it.userId to it.category.id },
                { it.totalPoints }
            )
        } else emptyMap()

        val base = mapTeamToPlayerDtoWithPoints(team, users, points)

        val status = teamRepository.getTeamResultStatus(teamId)
        val hasResult = status != null

        return base.copy(
            hasResult = hasResult,
            restriction = team.restriction
        )
    }

    suspend fun updateTeamCategory(teamId: String, newCategoryId: Int): Boolean {
        return teamRepository.updateTeamCategory(teamId, newCategoryId)
    }

    suspend fun setTeamResult(teamId: String, req: SetTeamResultRequest): String =
        teamRepository.setTeamResult(teamId, req)

    suspend fun unsetTeamResult(teamId: String) =
        teamRepository.unsetTeamResult(teamId)

    suspend fun getTeamsWithResultStatus(tournamentId: String): List<TeamWithResultStatusDto> =
        teamRepository.getTeamsWithResultStatus(tournamentId)

    suspend fun getTeamResultStatus(teamId: String): TeamResultStatusDto? =
        teamRepository.getTeamResultStatus(teamId)

    suspend fun getUserRegistrationsInTournamentResponse(
        userUid: String,
        tournamentId: String
    ): RegisteredInTournamentResponse {
        val rows: List<RpcRegistrationRowDto> =
            teamRepository.getUserRegistrationsInTournament(userUid, tournamentId)

        val items: List<RegistrationItemDto> = rows.map { r ->
            RegistrationItemDto(
                teamId = r.team_id,
                tournament = RegistrationTournamentDto(
                    id = r.tournament_id,
                    name = r.tournament_name,
                    startDate = r.tournament_start,
                    endDate = r.tournament_end,
                    status = r.tournament_status,
                    type = r.tournament_type,
                    organizerName = r.organizer_name
                ),
                category = CategoryResponseDto(
                    id = r.category_id,
                    name = r.category_name,
                ),
                partner = r.partner_uid?.let {
                    TeamPlayerDto(
                        uid = it,
                        firstName = r.partner_first_name.orEmpty(),
                        lastName = r.partner_last_name.orEmpty(),
                        photoUrl = r.partner_photo_url,
                        phone = r.partner_phone,
                        gender = r.partner_gender
                    )
                },
                iAmPlayerA = r.i_am_player_a,
                paidByMe = r.paid_by_me,
                paidByPartner = r.paid_by_partner
            )
        }

        return RegisteredInTournamentResponse(
            registered = items.isNotEmpty(),
            items = items
        )
    }

    suspend fun getMyRegistrations(
        userUid: String,
        status: String?,   // "active" | "upcoming" | "past" | null/"all"
        page: Int?,
        pageSize: Int?
    ): List<RegistrationItemDto> {
        val size = (pageSize ?: 20).coerceIn(1, 100)
        val p = (page ?: 1).coerceAtLeast(1)
        val offset = (p - 1) * size
        val normalizedStatus = when (status?.lowercase()) {
            "active", "upcoming", "past" -> status.lowercase()
            "all", null, "" -> null
            else -> null
        }

        val rows: List<RpcRegistrationRowDto> = teamRepository.getUserRegistrations(
            userUid = userUid,
            status = normalizedStatus,
            limit = size,
            offset = offset
        )

        return rows.map { r ->
            RegistrationItemDto(
                teamId = r.team_id,
                tournament = RegistrationTournamentDto(
                    id = r.tournament_id,
                    name = r.tournament_name,
                    startDate = r.tournament_start,
                    endDate = r.tournament_end,
                    status = r.tournament_status,
                    type = r.tournament_type,
                    organizerName = r.organizer_name
                ),
                category = CategoryResponseDto(
                    id = r.category_id,
                    name = r.category_name,
                ),
                partner = r.partner_uid?.let {
                    TeamPlayerDto(
                        uid = it,
                        firstName = r.partner_first_name.orEmpty(),
                        lastName = r.partner_last_name.orEmpty(),
                        photoUrl = r.partner_photo_url,
                        phone = r.partner_phone,
                        gender = r.partner_gender
                    )
                },
                iAmPlayerA = r.i_am_player_a,
                paidByMe = r.paid_by_me,
                paidByPartner = r.paid_by_partner
            )
        }
    }

    suspend fun registerFreeTeam(req: RegisterTeamRequest): Boolean {
        // Check if this player already has a team in this category
        if (req.playerUid != null) {
            val existing = teamRepository.findByPlayerAndCategory(
                playerUid = req.playerUid,
                tournamentId = req.tournamentId,
                categoryId = req.categoryId
            )
            if (existing != null) {
                // Update existing team: mark calling player as paid
                val paidField =
                    if (existing.playerAUid == req.playerUid) PlayerType.PLAYER_A.fieldName else PlayerType.PLAYER_B.fieldName
                return teamRepository.updateTeamPaidStatus(existing.id, paidField, true)
            }
        }
        // Create new team with player A paid (the calling player)
        return teamRepository.createTeam(
            TeamRequest(
                tournamentId = req.tournamentId,
                playerAUid = req.playerUid,
                playerBUid = req.partnerUid,
                categoryId = req.categoryId,
                playerAPaid = true,
                playerBPaid = false,
            )
        )
    }

    suspend fun replacePlayer(request: ReplacePlayerRequest): Boolean {
        // Validate player position
        if (request.playerPosition != "a" && request.playerPosition != "b") {
            return false
        }

        // Validate new player has either uid OR name
        val hasNewPlayerIdentity = request.newPlayerUid != null || !request.newPlayerName.isNullOrBlank()
        if (!hasNewPlayerIdentity) {
            return false
        }

        // Get existing team to validate and check for duplicate player
        val existingTeam = teamRepository.findTeamById(request.teamId) ?: return false

        // If replacing with a registered user, check they're not already on the team
        if (request.newPlayerUid != null) {
            val otherPlayerUid = if (request.playerPosition == "a") existingTeam.playerBUid else existingTeam.playerAUid
            if (request.newPlayerUid == otherPlayerUid) {
                return false // Can't have same player twice on a team
            }
        }

        return teamRepository.replacePlayer(
            teamId = request.teamId,
            playerPosition = request.playerPosition,
            newPlayerUid = request.newPlayerUid,
            newPlayerName = request.newPlayerName,
            newPlayerEmail = request.newPlayerEmail,
            newPlayerPhone = request.newPlayerPhone
        )
    }
}
