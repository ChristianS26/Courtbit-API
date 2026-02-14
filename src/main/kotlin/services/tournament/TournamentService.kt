package services.tournament

import com.incodap.models.tournament.UpdateTournamentRequest
import com.incodap.repositories.teams.TeamRepository
import models.bracket.MatchResponse
import models.bracket.StandingEntry
import models.category.TournamentCategoryRequest
import models.tournament.*
import repositories.bracket.BracketRepository
import repositories.tournament.TournamentHasPaymentsException
import repositories.tournament.TournamentRepository
import services.category.CategoryService

class TournamentService(
    private val repository: TournamentRepository,
    private val categoryService: CategoryService,
    private val teamRepository: TeamRepository,
    private val bracketRepository: BracketRepository,
) {

    suspend fun getAllTournaments(): List<TournamentResponse> = repository.getAll()

    suspend fun getTournamentsByOrganizer(organizerId: String): List<TournamentResponse> {
        val tournaments = repository.getByOrganizerId(organizerId)
        return tournaments.map { tournament ->
            val categories = categoryService.getCategoriesForTournament(tournament.id)
            val categoryIds = categories.map { it.id }
            tournament.copy(categoryIds = categoryIds)
        }
    }

    suspend fun getTournamentById(id: String): TournamentResponse? {
        val tournament = repository.getById(id) ?: return null
        val categories = categoryService.getCategoriesForTournament(id)
        val categoryIds = categories.map { it.id }
        return tournament.copy(categoryIds = categoryIds)
    }

    suspend fun createTournament(
        tournament: CreateTournamentRequest,
        categoryIds: List<Int>,
        categoryPrices: List<CategoryPriceRequest>? = null,
        categoryColors: List<CategoryColorRequest>? = null
    ): TournamentResponse? {

        val created = repository.create(tournament) ?: run {
            return null
        }

        // Si no hay categorías, no hay nada más que hacer
        if (categoryIds.isEmpty()) {
            return created.copy(categoryIds = emptyList())
        }

        // RPC transaccional en DB (diff + deletes/insert de categorías y draws)
        val rpc = repository.setTournamentCategories(created.id, categoryIds)

        if (rpc.isFailure) {
            val cause = rpc.exceptionOrNull()?.message ?: "RPC set_tournament_categories failed"

            // 🚨 Compensación: borra el torneo para no dejar basura
            val rolledBack = runCatching { repository.delete(created.id) }.getOrDefault(false)
            if (rolledBack) {
            } else {
            }
            return null
        }

        // Set category prices if provided
        if (!categoryPrices.isNullOrEmpty()) {
            val priceMap = categoryPrices.associate { it.categoryId to it.price }
            val pricesSet = repository.setCategoryPrices(created.id, priceMap)
            if (!pricesSet) {
            }
        }

        // Set category colors if provided
        if (!categoryColors.isNullOrEmpty()) {
            val colorMap = categoryColors.filter { it.color != null }.associate { it.categoryId to it.color!! }
            if (colorMap.isNotEmpty()) {
                val colorsSet = repository.setCategoryColors(created.id, colorMap)
                if (!colorsSet) {
                }
            }
        }

        // Todo OK: devuelve el torneo con categorías asignadas
        return created.copy(categoryIds = categoryIds.map { it.toString() })
    }

    suspend fun updateTournament(
        id: String,
        tournament: UpdateTournamentRequest,
        categoryIds: List<Int>,
        categoryPrices: List<CategoryPriceRequest>? = null,
        categoryColors: List<CategoryColorRequest>? = null
    ) {
        // 1) validación de negocio (equipos)
        val existingCategoryIds = categoryService.getCategoryIdsByTournament(id).mapNotNull { it.toIntOrNull() }
        val removed = existingCategoryIds.toSet() - categoryIds.toSet()
        if (removed.isNotEmpty()) {
            val names = categoryService.getCategoryNamesByIds(removed.toList())
            val blocked = names.filter { name -> teamRepository.hasTeamsForTournamentAndCategoryName(id, name) }
            if (blocked.isNotEmpty()) throw IllegalStateException("No se pueden eliminar categorías con equipos registrados")
        }

        // 2) patch torneo
        val updated = repository.update(id, tournament)
        if (!updated) throw IllegalStateException("No se pudo actualizar el torneo")

        // 3) RPC categorías (atómico en DB)
        val rpc = repository.setTournamentCategories(id, categoryIds)
        rpc.getOrElse { err ->
            throw IllegalStateException("No se pudieron actualizar las categorías: ${err.message}")
        }

        // 4) Update category prices if provided
        if (!categoryPrices.isNullOrEmpty()) {
            val priceMap = categoryPrices.associate { it.categoryId to it.price }
            repository.setCategoryPrices(id, priceMap)
        }

        // 5) Update category colors if provided
        if (!categoryColors.isNullOrEmpty()) {
            val colorMap = categoryColors.filter { it.color != null }.associate { it.categoryId to it.color!! }
            if (colorMap.isNotEmpty()) {
                repository.setCategoryColors(id, colorMap)
            }
        }
    }

    suspend fun updateIsEnabled(id: String, isEnabled: Boolean): Boolean =
        repository.patchField(id, mapOf("is_enabled" to isEnabled), "enabled")

    suspend fun updateRegistrationOpen(id: String, registrationOpen: Boolean): Boolean =
        repository.patchField(id, mapOf("registration_open" to registrationOpen), "registration")

    suspend fun updateAllowPlayerScores(id: String, allowPlayerScores: Boolean): Boolean =
        repository.patchField(id, mapOf("allow_player_scores" to allowPlayerScores), "allow_player_scores")

    suspend fun updateShowBrackets(id: String, showBrackets: Boolean): Boolean =
        repository.patchField(id, mapOf("show_brackets" to showBrackets), "show_brackets")

    suspend fun updatePaymentsEnabled(id: String, paymentsEnabled: Boolean): Boolean =
        repository.patchField(id, mapOf("payments_enabled" to paymentsEnabled), "payments_enabled")

    suspend fun updateShowRegisteredPlayers(id: String, show: Boolean): Boolean =
        repository.patchField(id, mapOf("show_registered_players" to show), "show_registered_players")

    // Nuevo: resultado tipado con manejo de pagos
    suspend fun deleteTournament(id: String): DeleteTournamentResult {

        return try {
            val deleted = repository.delete(id)

            if (deleted) {
                DeleteTournamentResult.Deleted
            } else {
                DeleteTournamentResult.Error("No se pudo eliminar el torneo en Supabase.")
            }
        } catch (e: TournamentHasPaymentsException) {
            DeleteTournamentResult.HasPayments
        } catch (e: Exception) {
            DeleteTournamentResult.Error(e.message)
        }
    }

    suspend fun updateFlyerUrl(id: String, flyerUrl: String): Boolean =
        repository.updateFlyerUrl(id, flyerUrl)

    suspend fun updateClubLogoUrl(id: String, clubLogoUrl: String): Boolean =
        repository.updateClubLogoUrl(id, clubLogoUrl)

    suspend fun updateCategoryColor(tournamentId: String, categoryId: Int, color: String): Boolean =
        repository.setCategoryColors(tournamentId, mapOf(categoryId to color))

    suspend fun getSchedulingConfig(tournamentId: String): models.tournament.SchedulingConfigResponse? =
        repository.getSchedulingConfig(tournamentId)

    suspend fun saveSchedulingConfig(tournamentId: String, config: models.tournament.SchedulingConfigRequest): Boolean =
        repository.saveSchedulingConfig(tournamentId, config)

    suspend fun getPlayerTournamentContext(
        userUid: String,
        tournamentId: String
    ): PlayerTournamentContextResponse {
        // 1. Get user's registrations in this tournament (team_id, category_id, partner info)
        val registrations = teamRepository.getUserRegistrationsInTournament(userUid, tournamentId)
        if (registrations.isEmpty()) {
            return PlayerTournamentContextResponse(categories = emptyList())
        }

        val teamIds = registrations.map { it.team_id }

        // 2. Get all brackets for this tournament
        val brackets = bracketRepository.getBracketsByTournament(tournamentId)
        val bracketsByCategory = brackets.associateBy { it.categoryId }
        val bracketIds = brackets.map { it.id }

        // 3. Get standings for user's teams
        val standings = bracketRepository.getStandingsByTeamIds(teamIds)
        val standingsByTeam = standings.associateBy { it.teamId }

        // 4. Get matches involving user's teams
        val matches = if (bracketIds.isNotEmpty()) {
            bracketRepository.getMatchesByTeamIds(bracketIds, teamIds)
        } else {
            emptyList()
        }
        // Group matches by team: a match belongs to a team if team1_id or team2_id matches
        val matchesByTeam = mutableMapOf<String, MutableList<MatchResponse>>()
        for (match in matches) {
            for (teamId in teamIds) {
                if (match.team1Id == teamId || match.team2Id == teamId) {
                    matchesByTeam.getOrPut(teamId) { mutableListOf() }.add(match)
                }
            }
        }

        // 5. Collect opponent team IDs for name lookups
        val opponentTeamIds = matches.flatMap { match ->
            listOfNotNull(match.team1Id, match.team2Id)
        }.toSet() - teamIds.toSet()

        // 6. Get opponent team names (player names from users table)
        val opponentNames = if (opponentTeamIds.isNotEmpty()) {
            getTeamDisplayNames(opponentTeamIds.toList())
        } else {
            emptyMap()
        }

        // 7. Assemble per-category context
        val categoryContexts = registrations.map { reg ->
            val bracket = bracketsByCategory[reg.category_id]
            val standing = standingsByTeam[reg.team_id]
            val teamMatches = matchesByTeam[reg.team_id] ?: emptyList()

            // Group context (only if standings have a group_number)
            val groupCtx = if (standing?.groupNumber != null) {
                // Count teams in the same group within the same bracket
                val sameGroupTeams = standings.count {
                    it.bracketId == standing.bracketId && it.groupNumber == standing.groupNumber
                }
                GroupContext(
                    groupNumber = standing.groupNumber,
                    groupName = "Grupo ${groupLetter(standing.groupNumber)}",
                    position = standing.position ?: 0,
                    totalTeams = sameGroupTeams
                )
            } else null

            // Stats from standings (or zeros if no standings yet)
            val statsCtx = StatsContext(
                matchesPlayed = standing?.matchesPlayed ?: 0,
                matchesWon = standing?.matchesWon ?: 0,
                matchesLost = standing?.matchesLost ?: 0,
                gamesWon = standing?.gamesWon ?: 0,
                gamesLost = standing?.gamesLost ?: 0
            )

            // Next match: first non-completed match
            val pendingMatches = teamMatches.filter { it.status != "completed" && it.status != "forfeit" && !it.isBye }
            val nextMatch = pendingMatches.firstOrNull()
            val nextMatchCtx = nextMatch?.let { m ->
                val opponentId = if (m.team1Id == reg.team_id) m.team2Id else m.team1Id
                MatchContext(
                    matchId = m.id,
                    opponentTeamName = opponentId?.let { opponentNames[it] },
                    scheduledTime = m.scheduledTime,
                    courtNumber = m.courtNumber,
                    roundName = m.roundName
                )
            }

            // Last match: most recent completed match
            val completedMatches = teamMatches.filter { it.status == "completed" || it.status == "forfeit" }
            val lastMatch = completedMatches.lastOrNull()
            val lastMatchCtx = lastMatch?.let { m ->
                val isTeam1 = m.team1Id == reg.team_id
                val won = if (isTeam1) m.winnerTeam == 1 else m.winnerTeam == 2
                val opponentId = if (isTeam1) m.team2Id else m.team1Id
                LastMatchContext(
                    matchId = m.id,
                    opponentTeamName = opponentId?.let { opponentNames[it] },
                    won = won,
                    scoreDisplay = buildScoreDisplay(m, isTeam1)
                )
            }

            // Partner name
            val partnerName = listOfNotNull(reg.partner_first_name, reg.partner_last_name)
                .joinToString(" ")
                .ifBlank { null }

            CategoryContext(
                categoryId = reg.category_id,
                categoryName = reg.category_name,
                teamId = reg.team_id,
                partnerName = partnerName,
                partnerPhotoUrl = reg.partner_photo_url,
                bracket = bracket?.let {
                    BracketContext(
                        bracketId = it.id,
                        format = it.format,
                        status = it.status
                    )
                },
                group = groupCtx,
                stats = statsCtx,
                nextMatch = nextMatchCtx,
                lastMatch = lastMatchCtx
            )
        }

        return PlayerTournamentContextResponse(categories = categoryContexts)
    }

    private fun groupLetter(groupNumber: Int): String {
        // groupNumber is 1-based: 1 -> A, 2 -> B, etc.
        return if (groupNumber in 1..26) {
            ('A' + groupNumber - 1).toString()
        } else {
            groupNumber.toString()
        }
    }

    private fun buildScoreDisplay(match: MatchResponse, isTeam1: Boolean): String {
        val setScores = match.setScores ?: return "${match.scoreTeam1 ?: 0}-${match.scoreTeam2 ?: 0}"
        return try {
            // setScores is a JsonElement (array of {team1, team2} objects)
            val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val sets = jsonParser.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(models.bracket.SetScore.serializer()),
                setScores
            )
            sets.joinToString(", ") { set ->
                if (isTeam1) "${set.team1}-${set.team2}" else "${set.team2}-${set.team1}"
            }
        } catch (_: Exception) {
            "${match.scoreTeam1 ?: 0}-${match.scoreTeam2 ?: 0}"
        }
    }

    /**
     * Gets display names for teams by looking up player names from the users table
     * via the teams table (player_a_uid -> users.first_name + last_name).
     * Returns a map of teamId -> "PlayerA & PlayerB" display name.
     */
    private suspend fun getTeamDisplayNames(teamIds: List<String>): Map<String, String> {
        if (teamIds.isEmpty()) return emptyMap()

        // Fetch all teams in one batch per team (findTeamById is per-team but we batch user lookups)
        val teams = teamIds.mapNotNull { teamRepository.findTeamById(it)?.let { t -> it to t } }.toMap()

        // Collect all user UIDs that need lookup
        val uidsThatNeedLookup = teams.values.flatMap { team ->
            listOfNotNull(
                team.playerAUid.takeIf { team.playerAName.isNullOrBlank() },
                team.playerBUid.takeIf { team.playerBName.isNullOrBlank() }
            )
        }.distinct()

        // Batch lookup users
        val usersMap = if (uidsThatNeedLookup.isNotEmpty()) {
            teamRepository.findUsersByIds(uidsThatNeedLookup).associateBy { it.uid }
        } else {
            emptyMap()
        }

        // Build display names
        val names = mutableMapOf<String, String>()
        for ((teamId, team) in teams) {
            val playerNames = mutableListOf<String>()

            if (!team.playerAName.isNullOrBlank()) {
                playerNames.add(team.playerAName)
            } else if (team.playerAUid != null) {
                usersMap[team.playerAUid]?.let { u ->
                    playerNames.add("${u.firstName} ${u.lastName}".trim())
                }
            }

            if (!team.playerBName.isNullOrBlank()) {
                playerNames.add(team.playerBName)
            } else if (team.playerBUid != null) {
                usersMap[team.playerBUid]?.let { u ->
                    playerNames.add("${u.firstName} ${u.lastName}".trim())
                }
            }

            if (playerNames.isNotEmpty()) {
                names[teamId] = playerNames.joinToString(" & ")
            }
        }
        return names
    }
}