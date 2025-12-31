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
import models.teams.RegistrationItemDto
import models.teams.RegistrationTournamentDto
import models.teams.Team
import models.teams.TeamGroupByCategoryFullResponse
import models.teams.TeamPlayerDto
import models.teams.TeamRequest
import models.teams.TeamWithPlayerDto
import models.teams.toTeamPlayerDto
import repositories.registrationcode.RegistrationCodeRepository
import services.ranking.RankingService

class TeamService(
    private val teamRepository: TeamRepository,
    private val registrationCodeRepository: RegistrationCodeRepository,
    private val rankingService: RankingService,
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
        val existing = teamRepository.findTeam(req.tournamentId, req.playerUid, req.partnerUid)
        return if (existing == null) {
            createNewTeam(req)
        } else {
            updateExistingTeam(existing, req)
        }
    }

    suspend fun teamExists(tournamentId: String, playerA: String, playerB: String): Team? {
        return teamRepository.findTeam(tournamentId, playerA, playerB)
    }

    suspend fun getTeamsGroupedByCategoryWithPlayerInfo(
        tournamentId: String
    ): List<TeamGroupByCategoryFullResponse> {
        val teams = teamRepository.findTeamsByTournament(tournamentId)
        if (teams.isEmpty()) return emptyList()

        val allPlayerUids = teams.flatMap { listOf(it.playerAUid, it.playerBUid) }.distinct()
        val allCategoryIds = teams.map { it.category.id }.distinct()
        val userMap = teamRepository.findUsersByIds(allPlayerUids).associateBy { it.uid }

        val allRankings = rankingService.getRankingForMultipleUsersAndCategories(
            allPlayerUids, allCategoryIds, "2025"
        )
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
            .sortedWith(compareBy({ it.position }, { it.name }))

        return categoriesOrdered.map { cat ->
            val teamsInCategory = teamsByCategoryId[cat.id].orEmpty()

            val sortedTeams = teamsInCategory
                .map { team ->
                    val aPts = pointsByUserAndCategory[(team.playerAUid to team.category.id)] ?: 0
                    val bPts = pointsByUserAndCategory[(team.playerBUid to team.category.id)] ?: 0
                    Triple(team, aPts, bPts)
                }
                .sortedByDescending { it.second + it.third }

            TeamGroupByCategoryFullResponse(
                categoryName = cat.name,
                teams = sortedTeams.map { (team, aPts, bPts) ->
                    TeamWithPlayerDto(
                        id = team.id,
                        category = team.category,
                        playerA = userMap[team.playerAUid]?.toTeamPlayerDto() ?: error("No user A"),
                        playerB = userMap[team.playerBUid]?.toTeamPlayerDto() ?: error("No user B"),
                        playerAPoints = aPts,
                        playerAPaid = team.playerAPaid,
                        playerBPoints = bPts,
                        playerBPaid = team.playerBPaid,
                        hasResult = teamIdsWithResult.contains(team.id),
                        restriction = team.restriction
                    )
                }
            )
        }
    }

    fun mapTeamToPlayerDtoWithPoints(
        team: Team,
        userMap: Map<String, UserDto>,
        pointsByUserAndCategory: Map<Pair<String, Int>, Int>
    ): TeamWithPlayerDto {
        val categoryId = team.category.id

        val playerA = userMap[team.playerAUid]
            ?: error("No se encontró el usuario A con uid: ${team.playerAUid}")
        val playerB = userMap[team.playerBUid]
            ?: error("No se encontró el usuario B con uid: ${team.playerBUid}")

        return TeamWithPlayerDto(
            id = team.id,
            category = team.category,
            playerA = playerA.toTeamPlayerDto(),
            playerB = playerB.toTeamPlayerDto(),
            playerAPoints = pointsByUserAndCategory[team.playerAUid to categoryId] ?: 0,
            playerAPaid = team.playerAPaid,
            playerBPoints = pointsByUserAndCategory[team.playerBUid to categoryId] ?: 0,
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
                println("❌ Código inválido o ya utilizado: ${request.code}")
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
                    println("ℹ️ Jugador ya registrado y con pago confirmado: ${request.playerUid}")
                    return RegisterTeamResult.AlreadyRegistered
                }

                val success =
                    registrationCodeRepository.markCodeAsUsed(request.code, request.email, request.tournamentId)
                if (!success) {
                    println("❌ Error al marcar código como usado: ${request.code}")
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
                println("❌ No se pudo marcar el código como usado antes de crear el equipo")
                return RegisterTeamResult.InvalidCode
            }

            val created = createTeamWithCode(request)
            if (!created) {
                println("❌ Fallo al crear el equipo después de marcar código como usado")
                return RegisterTeamResult.InvalidCode
            }

            return RegisterTeamResult.Created

        } catch (e: Exception) {
            println("❌ Error inesperado en registerTeamWithCode: ${e.message}")
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
                playerBPaid = false
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

        val userUids = listOf(team.playerAUid, team.playerBUid)
        val users = teamRepository.findUsersByIds(userUids).associateBy { it.uid }

        val points = rankingService.getRankingForMultipleUsersAndCategories(
            userUids,
            listOf(team.category.id),
            "2025"
        ).associateBy(
            { it.userId to it.category.id },
            { it.totalPoints }
        )

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
                    status = r.tournament_status
                ),
                category = CategoryResponseDto(
                    id = r.category_id,
                    name = r.category_name,
                    position = 0
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
                    status = r.tournament_status
                ),
                category = CategoryResponseDto(
                    id = r.category_id,
                    name = r.category_name,
                    position = 0
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
}
