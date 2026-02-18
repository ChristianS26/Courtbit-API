package services.bracket

import kotlinx.serialization.json.Json
import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.MatchResponse
import models.bracket.WithdrawTeamResponse
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository

/**
 * Service for bracket lifecycle, retrieval, match management, and withdrawal.
 */
class BracketService(
    private val repository: BracketRepository,
    private val json: Json,
    private val auditLog: BracketAuditRepository
) {
    // ============ Retrieval ============

    /**
     * Get bracket with all matches for a tournament category
     */
    suspend fun getBracket(tournamentId: String, categoryId: Int): BracketWithMatchesResponse? {
        return repository.getBracketWithMatches(tournamentId, categoryId)
    }

    /**
     * Get all brackets for a tournament
     */
    suspend fun getBracketsByTournament(tournamentId: String): List<BracketResponse> {
        return repository.getBracketsByTournament(tournamentId)
    }

    /**
     * Get all brackets with matches, standings, and players for a tournament (bulk fetch)
     */
    suspend fun getAllBracketsWithMatches(tournamentId: String): List<BracketWithMatchesResponse> {
        return repository.getAllBracketsWithMatches(tournamentId)
    }

    // ============ Lifecycle ============

    /**
     * Update bracket config (e.g. match format) without regenerating matches.
     */
    suspend fun updateBracketConfig(
        tournamentId: String,
        categoryId: Int,
        configJson: String
    ): Result<BracketWithMatchesResponse> {
        val existing = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val updated = repository.updateBracketConfig(existing.bracket.id, configJson)
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to update bracket config"))
        }

        // Re-fetch to return updated data
        val refreshed = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found after update"))

        return Result.success(refreshed)
    }

    /**
     * Create a bracket without generating matches.
     * Allows organizer to configure the bracket before generating matches.
     * If bracket exists, deletes it and creates a new one.
     */
    suspend fun createBracket(
        tournamentId: String,
        categoryId: Int,
        format: String,
        seedingMethod: String
    ): Result<BracketResponse> {
        // Check if bracket already exists - if so, delete it
        val existing = repository.getBracket(tournamentId, categoryId)
        if (existing != null) {
            repository.deleteBracket(existing.id)
        }

        // Create bracket record
        val bracket = repository.createBracket(
            tournamentId = tournamentId,
            categoryId = categoryId,
            format = format,
            seedingMethod = seedingMethod
        ) ?: return Result.failure(IllegalStateException("Failed to create bracket record"))

        return Result.success(bracket)
    }

    /**
     * Generate matches for a bracket.
     * Uses existing bracket if available, or creates a new one.
     * Deletes existing matches and regenerates them.
     */
    suspend fun generateBracket(
        tournamentId: String,
        categoryId: Int,
        seedingMethod: String,
        teamIds: List<String>
    ): Result<BracketWithMatchesResponse> {
        // Validate input
        if (teamIds.size < 2) {
            return Result.failure(IllegalArgumentException("At least 2 teams required to generate bracket"))
        }

        // Check if bracket already exists
        val existing = repository.getBracket(tournamentId, categoryId)
        val bracket: BracketResponse

        if (existing != null) {
            // Use existing bracket, but delete its matches to regenerate
            repository.deleteMatchesByBracketId(existing.id)
            bracket = existing
        } else {
            // Create new bracket record
            bracket = repository.createBracket(
                tournamentId = tournamentId,
                categoryId = categoryId,
                format = "knockout",
                seedingMethod = seedingMethod
            ) ?: return Result.failure(IllegalStateException("Failed to create bracket record"))
        }

        // Generate match structure
        val teams = teamIds.mapIndexed { index, teamId ->
            TeamSeed(teamId = teamId, seed = index + 1)
        }

        val generatedMatches = generateKnockoutMatches(teams)

        // Create matches in database
        val createdMatches = repository.createMatches(bracket.id, generatedMatches)
        if (createdMatches.isEmpty() && generatedMatches.isNotEmpty()) {
            // Rollback: delete the bracket
            repository.deleteBracket(bracket.id)
            return Result.failure(IllegalStateException("Failed to create matches"))
        }

        // Build match number to UUID mapping
        val matchNumberToId = createdMatches.associate { it.matchNumber to it.id }

        // Update next_match_id references
        for (generated in generatedMatches) {
            if (generated.nextMatchNumber != null && generated.nextMatchPosition != null) {
                val matchId = matchNumberToId[generated.matchNumber] ?: continue
                val nextMatchId = matchNumberToId[generated.nextMatchNumber] ?: continue
                repository.updateMatchNextMatchId(matchId, nextMatchId, generated.nextMatchPosition)
            }
        }

        // Auto-advance BYE matches: the real team advances to the next round
        for (generated in generatedMatches) {
            if (!generated.isBye) continue
            val matchId = matchNumberToId[generated.matchNumber] ?: continue
            val advancingTeamId = generated.team1Id ?: generated.team2Id ?: continue
            val nextMatchNumber = generated.nextMatchNumber ?: continue
            val nextMatchId = matchNumberToId[nextMatchNumber] ?: continue
            val position = generated.nextMatchPosition ?: continue

            // Place the advancing team in the correct slot of the next match
            val field = if (position == 1) "team1_id" else "team2_id"
            repository.updateMatchField(nextMatchId, field, advancingTeamId)
        }

        // Fetch complete bracket with updated matches
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch created bracket"))

        return Result.success(result)
    }

    /**
     * Publish a bracket, locking its structure
     */
    suspend fun publishBracket(tournamentId: String, categoryId: Int, organizerId: String? = null): Result<BracketResponse> {
        val bracket = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        if (bracket.status == "published") {
            return Result.failure(IllegalStateException("Bracket is already published"))
        }

        val updated = repository.updateBracketStatus(bracket.id, "published")
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to publish bracket"))
        }

        auditLog.log("bracket", bracket.id, "publish", organizerId)

        // Return updated bracket
        val result = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Unpublish a bracket, reverting to in_progress status
     */
    suspend fun unpublishBracket(tournamentId: String, categoryId: Int, organizerId: String? = null): Result<BracketResponse> {
        val bracket = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        if (bracket.status != "published") {
            return Result.failure(IllegalStateException("Bracket is not published"))
        }

        val updated = repository.updateBracketStatus(bracket.id, "in_progress")
        if (!updated) {
            return Result.failure(IllegalStateException("Failed to unpublish bracket"))
        }

        auditLog.log("bracket", bracket.id, "unpublish", organizerId)

        // Return updated bracket
        val result = repository.getBracket(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Delete a bracket unconditionally. Cascades to matches, standings, etc.
     */
    suspend fun deleteBracket(bracketId: String): Boolean {
        return repository.deleteBracket(bracketId)
    }

    // ============ Match Scoring ============

    /**
     * Update match score with padel validation.
     * Validates the score according to the bracket's format (classic or express).
     * Automatically updates standings for group stage matches.
     */
    suspend fun updateMatchScore(
        matchId: String,
        setScores: List<SetScore>
    ): Result<MatchResponse> {
        // Get match to find its bracket
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        // Cannot score a BYE match
        if (match.isBye || match.status == "bye") {
            return Result.failure(IllegalArgumentException("Cannot score a BYE match"))
        }

        // Cannot overwrite a completed match — must delete the score first
        if (match.status == "completed" || match.status == "forfeit" || match.status == "walkover") {
            return Result.failure(IllegalArgumentException("Match already has a result. Delete the existing score before assigning a new one."))
        }

        // Get bracket config to determine format
        val bracket = repository.getBracketById(match.bracketId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val deleted = repository.deleteBracket(bracketId)
        if (deleted) {
            auditLog.log("bracket", bracketId, "delete", organizerId)
        }
        return if (deleted) Result.success(true)
        else Result.failure(IllegalStateException("Failed to delete bracket"))
    }

    /**
     * Delete/reset a match score with cascade.
     * If the winner was advanced to the next match and that match also has a result,
     * recursively deletes downstream results before clearing this one.
     */
    suspend fun deleteMatchScore(matchId: String): Result<MatchResponse> {
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        if (match.status != "completed" && match.status != "forfeit" && match.status != "walkover") {
            return Result.failure(IllegalArgumentException("Match has no score to delete"))
        }

        try {
            cascadeDeleteScore(match)
        } catch (e: Exception) {
            println("Warning: Cascade undo failed: ${e.message}")
        }

        // Reset this match's score
        val result = repository.deleteMatchScore(matchId)

        // Recalculate standings if needed
        if (result.isSuccess) {
            try {
                val bracket = repository.getBracketById(match.bracketId)
                if (bracket != null) {
                    when (bracket.format) {
                        "groups_knockout" -> calculateGroupStandings(bracket.tournamentId, bracket.categoryId)
                        "round_robin" -> calculateStandings(bracket.tournamentId, bracket.categoryId)
                    }
                }
            } catch (_: Exception) {}
        }

        return result
    }

    /**
     * Recursively cascade score deletion forward through the bracket.
     * If the next match has a completed result involving the advancing team,
     * delete that result first (which recurses further), then clear the team slot.
     */
    private suspend fun cascadeDeleteScore(match: MatchResponse) {
        val nextMatchId = match.nextMatchId ?: return
        val position = match.nextMatchPosition ?: return
        if (match.winnerTeam == null) return

        val winnerTeamId = when (match.winnerTeam) {
            1 -> match.team1Id
            2 -> match.team2Id
            else -> null
        } ?: return

        val nextMatch = repository.getMatch(nextMatchId) ?: return

        // Verify the winner is actually in the expected slot
        val isInSlot = when (position) {
            1 -> nextMatch.team1Id == winnerTeamId
            2 -> nextMatch.team2Id == winnerTeamId
            else -> false
        }
        if (!isInSlot) return

        // If the next match has a completed result, cascade-delete it first
        if (nextMatch.status == "completed" || nextMatch.status == "forfeit" || nextMatch.status == "walkover") {
            cascadeDeleteScore(nextMatch)
            repository.deleteMatchScore(nextMatchId)
        }

        // Now clear the team slot in the next match
        repository.clearMatchTeamSlot(nextMatchId, position)
    }

    /**
     * Advance the winner of a completed match to the next match.
     * The match must be completed with a winner determined.
     */
    suspend fun advanceWinner(matchId: String): Result<MatchResponse> {
        // Get the match to check it's completed
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalStateException("Match not found"))

        if (match.status != "completed") {
            return Result.failure(IllegalArgumentException("Cannot advance - match is not completed"))
        }

        val winnerTeam = match.winnerTeam
            ?: return Result.failure(IllegalArgumentException("Cannot advance - no winner determined"))

        // Get the winning team's ID
        val winnerTeamId = when (winnerTeam) {
            1 -> match.team1Id ?: return Result.failure(IllegalArgumentException("Team 1 ID missing"))
            2 -> match.team2Id ?: return Result.failure(IllegalArgumentException("Team 2 ID missing"))
            else -> return Result.failure(IllegalArgumentException("Invalid winner_team value: $winnerTeam"))
        }

        // Check the next match slot isn't already occupied by a different team
        if (match.nextMatchId != null && match.nextMatchPosition != null) {
            val nextMatch = repository.getMatch(match.nextMatchId)
            if (nextMatch != null) {
                val occupant = when (match.nextMatchPosition) {
                    1 -> nextMatch.team1Id
                    2 -> nextMatch.team2Id
                    else -> null
                }
                if (occupant != null && occupant != winnerTeamId) {
                    return Result.failure(IllegalStateException(
                        "Cannot advance: the target slot in the next match is already occupied by a different team"
                    ))
                }
            }
        }

        // Advance to next match
        repository.advanceWinner(matchId, winnerTeamId).getOrElse {
            return Result.failure(it)
        }

        // Return updated match
        return repository.getMatch(matchId)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Match not found after advancement"))
    }

    // ============ Player Score Submission ============

    /**
     * Submit a score as a player (not organizer).
     * Validates that the player is in the match and the tournament allows player scores.
     */
    suspend fun submitPlayerScore(
        matchId: String,
        userId: String,
        setScores: List<SetScore>
    ): Result<MatchResponse> {
        // Get match
        val match = repository.getMatch(matchId)
            ?: return Result.failure(IllegalArgumentException("Match not found"))

        // Check match is not already completed
        if (match.status == "completed" || match.status == "forfeit") {
            return Result.failure(IllegalArgumentException("Match is already completed"))
        }

        // Get bracket to find tournament
        val bracket = repository.getBracketById(match.bracketId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        // Check tournament allows player scores
        val allowPlayerScores = repository.getTournamentAllowPlayerScores(bracket.tournamentId)
        if (!allowPlayerScores) {
            return Result.failure(IllegalAccessException("Tournament does not allow player score submission"))
        }

        // Verify player is in the match
        val team1Uids = match.team1Id?.let { repository.getTeamPlayerUids(it) } ?: emptyList()
        val team2Uids = match.team2Id?.let { repository.getTeamPlayerUids(it) } ?: emptyList()
        val allPlayerUids = team1Uids + team2Uids
        if (userId !in allPlayerUids) {
            return Result.failure(IllegalAccessException("User is not a player in this match"))
        }

        // Parse match format from bracket config
        val matchFormat = bracket.config?.let {
            try {
                val config = json.decodeFromString<GroupsKnockoutConfig>(it.toString())
                config.matchFormat
            } catch (e: Exception) { null }
        }

        // Validate score
        val validation = if (matchFormat?.pointsPerSet != null) {
            PadelScoreValidator.validateExpressScore(setScores, matchFormat.pointsPerSet, matchFormat.sets)
        } else {
            val gamesPerSet = matchFormat?.gamesPerSet ?: 6
            val totalSets = matchFormat?.sets ?: 3
            PadelScoreValidator.validateMatchScore(setScores, gamesPerSet, totalSets)
        }

        if (validation is ScoreValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.message))
        }

        val validResult = validation as ScoreValidationResult.Valid
        val setsWonTeam1 = validResult.setsWon.first
        val setsWonTeam2 = validResult.setsWon.second

        // Update with audit trail
        val updateResult = repository.updateMatchScoreWithAudit(
            matchId = matchId,
            scoreTeam1 = setsWonTeam1,
            scoreTeam2 = setsWonTeam2,
            setScores = setScores,
            winnerTeam = validResult.winner,
            submittedByUserId = userId
        )

        // Recalculate standings and auto-advance
        if (updateResult.isSuccess) {
            when (bracket.format) {
                "groups_knockout" -> calculateGroupStandings(bracket.tournamentId, bracket.categoryId)
                "round_robin" -> calculateStandings(bracket.tournamentId, bracket.categoryId)
            }

            if (bracket.format == "knockout" || bracket.format == "groups_knockout") {
                try { advanceWinner(matchId) } catch (_: Exception) {}
            }
        }

        return updateResult
    }

    // ============ Standings ============

    /**
     * Get existing standings for a bracket
     */
    suspend fun getStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val existingStandings = repository.getStandings(bracket.id)

        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = existingStandings
        ))
    }

    /**
     * Calculate standings from completed matches.
     * For knockout brackets, standings are based on how far each team progressed:
     * - Winner: Position 1 (100 points)
     * - Finalist: Position 2 (70 points)
     * - Semi-finalists: Position 3-4 (50 points)
     * - Quarter-finalists: Position 5-8 (30 points)
     * - etc.
     */
    suspend fun calculateStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Only calculate from completed matches
        val completedMatches = matches.filter { it.status == "completed" }

        if (completedMatches.isEmpty()) {
            return Result.failure(IllegalArgumentException("No completed matches to calculate standings"))
        }

        // Build standings from match results
        val standingsMap = mutableMapOf<String, StandingBuilder>()

        // Process each completed match
        for (match in completedMatches) {
            val team1Id = match.team1Id ?: continue
            val team2Id = match.team2Id ?: continue

            // Initialize entries if not exists
            if (team1Id !in standingsMap) {
                standingsMap[team1Id] = StandingBuilder(teamId = team1Id)
            }
            if (team2Id !in standingsMap) {
                standingsMap[team2Id] = StandingBuilder(teamId = team2Id)
            }

            // Get set scores for game counts
            val setScores = match.setScores?.let {
                try { json.decodeFromJsonElement<List<SetScore>>(it) }
                catch (e: Exception) { null }
            } ?: emptyList()

            val team1Games = setScores.sumOf { it.team1 }
            val team2Games = setScores.sumOf { it.team2 }

            // Update stats
            standingsMap[team1Id]!!.apply {
                matchesPlayed++
                gamesWon += team1Games
                gamesLost += team2Games
                if (match.winnerTeam == 1) {
                    matchesWon++
                } else {
                    matchesLost++
                    lastRoundLost = match.roundNumber
                    roundNameLost = match.roundName
                }
            }

            standingsMap[team2Id]!!.apply {
                matchesPlayed++
                gamesWon += team2Games
                gamesLost += team1Games
                if (match.winnerTeam == 2) {
                    matchesWon++
                } else {
                    matchesLost++
                    lastRoundLost = match.roundNumber
                    roundNameLost = match.roundName
                }
            }
        }

        // Find the winner (team that won all matches)
        val totalRounds = matches.maxOfOrNull { it.roundNumber } ?: 1
        val finalMatch = matches.find { it.roundNumber == totalRounds && it.status == "completed" }

        // Determine positions based on round reached
        // Teams that lost later rank higher, winner ranks first
        val standings = standingsMap.values.sortedWith(
            compareByDescending<StandingBuilder> { it.matchesLost == 0 && finalMatch != null }  // Winner first
                .thenByDescending { it.lastRoundLost ?: Int.MAX_VALUE }  // Lost later = better
                .thenByDescending { it.gamesWon - it.gamesLost }  // Tiebreaker: game difference
        ).mapIndexed { index, builder ->
            val roundReached = when {
                builder.matchesLost == 0 && finalMatch != null -> "Winner"
                builder.roundNameLost == "Final" -> "Finalist"
                builder.roundNameLost == "Semifinals" -> "Semi-finalist"
                builder.roundNameLost == "Quarterfinals" -> "Quarter-finalist"
                else -> "Round ${builder.lastRoundLost ?: 1}"
            }

            val points = when (roundReached) {
                "Winner" -> 100
                "Finalist" -> 70
                "Semi-finalist" -> 50
                "Quarter-finalist" -> 30
                else -> 10 + ((builder.lastRoundLost ?: 1) * 5)
            }

            StandingInput(
                bracketId = bracket.id,
                teamId = builder.teamId,
                playerId = null,
                position = index + 1,
                totalPoints = points,
                matchesPlayed = builder.matchesPlayed,
                matchesWon = builder.matchesWon,
                matchesLost = builder.matchesLost,
                gamesWon = builder.gamesWon,
                gamesLost = builder.gamesLost,
                pointDifference = builder.gamesWon - builder.gamesLost,
                roundReached = roundReached
            )
        }

        // Persist standings
        repository.upsertStandings(standings)

        // Return calculated standings
        val savedStandings = repository.getStandings(bracket.id)
        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = savedStandings
        ))
    }

    /**
     * Helper class for building standings during calculation
     */
    private data class StandingBuilder(
        val teamId: String,
        var matchesPlayed: Int = 0,
        var matchesWon: Int = 0,
        var matchesLost: Int = 0,
        var gamesWon: Int = 0,
        var gamesLost: Int = 0,
        var lastRoundLost: Int? = null,
        var roundNameLost: String? = null
    )

    // ============ Groups + Knockout Generation ============

    /**
     * Generate group stage matches for groups_knockout format.
     * Creates round-robin matches within each group.
     */
    suspend fun generateGroupStage(
        tournamentId: String,
        categoryId: Int,
        request: AssignGroupsRequest
    ): Result<BracketWithMatchesResponse> {
        val config = request.config
        val groups = request.groups

        // Validate input
        if (groups.size != config.groupCount) {
            return Result.failure(IllegalArgumentException(
                "Expected ${config.groupCount} groups, got ${groups.size}"
            ))
        }

        // Validate each group has at least 2 teams (allow uneven groups)
        for (group in groups) {
            if (group.teamIds.size < 2) {
                return Result.failure(IllegalArgumentException(
                    "Group ${group.groupNumber} has ${group.teamIds.size} teams, minimum is 2"
                ))
            }
        }

        // Check if bracket already exists
        val existing = repository.getBracket(tournamentId, categoryId)
        val bracket: BracketResponse

        if (existing != null) {
            // Delete existing matches to regenerate
            repository.deleteMatchesByBracketId(existing.id)
            // Update bracket config
            repository.updateBracketConfig(existing.id, json.encodeToString(config))
            bracket = existing
        } else {
            // Create new bracket
            bracket = repository.createBracket(
                tournamentId = tournamentId,
                categoryId = categoryId,
                format = "groups_knockout",
                seedingMethod = "manual"
            ) ?: return Result.failure(IllegalStateException("Failed to create bracket"))
            // Save config to the new bracket
            repository.updateBracketConfig(bracket.id, json.encodeToString(config))
        }

        // Generate round-robin matches for each group
        val allMatches = mutableListOf<GeneratedMatch>()
        var matchNumber = 1

        for (group in groups) {
            val groupMatches = generateRoundRobinMatches(
                teamIds = group.teamIds,
                groupNumber = group.groupNumber,
                startMatchNumber = matchNumber
            )
            allMatches.addAll(groupMatches)
            matchNumber += groupMatches.size
        }

        // Create matches in database
        val createdMatches = repository.createMatches(bracket.id, allMatches)
        if (createdMatches.isEmpty() && allMatches.isNotEmpty()) {
            repository.deleteBracket(bracket.id)
            return Result.failure(IllegalStateException("Failed to create group matches"))
        }

        // Create initial standings for each team
        val standingInputs = groups.flatMap { group ->
            group.teamIds.mapIndexed { index, teamId ->
                StandingInput(
                    bracketId = bracket.id,
                    teamId = teamId,
                    playerId = null,
                    position = index + 1,
                    totalPoints = 0,
                    matchesPlayed = 0,
                    matchesWon = 0,
                    matchesLost = 0,
                    gamesWon = 0,
                    gamesLost = 0,
                    pointDifference = 0,
                    roundReached = null,
                    groupNumber = group.groupNumber
                )
            }
        }
        repository.upsertStandings(standingInputs)

        // Return complete bracket
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch created bracket"))

        return Result.success(result)
    }

    /**
     * Get current state of all groups
     */
    suspend fun getGroupsState(
        tournamentId: String,
        categoryId: Int
    ): Result<GroupsStateResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        if (bracket.format != "groups_knockout") {
            return Result.failure(IllegalArgumentException("Bracket is not groups_knockout format"))
        }

        // Parse config
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        } ?: GroupsKnockoutConfig(groupCount = 4, teamsPerGroup = 4, advancingPerGroup = 2)

        val matches = bracketWithMatches.matches
        val standings = bracketWithMatches.standings

        // Group matches by group_number
        val groupMatches = matches.filter { it.groupNumber != null }.groupBy { it.groupNumber!! }
        val groupStandings = standings.filter { it.groupNumber != null }.groupBy { it.groupNumber!! }

        // Check if knockout phase exists (matches without group_number)
        val knockoutMatches = matches.filter { it.groupNumber == null }
        val knockoutGenerated = knockoutMatches.isNotEmpty()

        // Determine current phase
        val allGroupMatchesComplete = groupMatches.values.flatten().all { it.status == "completed" }
        val phase = when {
            knockoutGenerated -> "knockout"
            else -> "groups"
        }

        // Build group states
        val groups = (1..config.groupCount).map { groupNum ->
            val gMatches = groupMatches[groupNum] ?: emptyList()
            val gStandings = groupStandings[groupNum] ?: emptyList()
            val teamIds = gStandings.mapNotNull { it.teamId }

            GroupState(
                groupNumber = groupNum,
                groupName = "Grupo ${('A' + groupNum - 1)}",
                teamIds = teamIds,
                matches = gMatches.sortedBy { it.matchNumber },
                standings = gStandings.sortedBy { it.position }
            )
        }

        return Result.success(GroupsStateResponse(
            bracketId = bracket.id,
            config = config,
            groups = groups,
            phase = phase,
            knockoutGenerated = knockoutGenerated
        ))
    }

    /**
     * Calculate and update group standings based on completed matches.
     */
    suspend fun calculateGroupStandings(
        tournamentId: String,
        categoryId: Int
    ): Result<StandingsResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Get group matches only
        val groupMatches = matches.filter { it.groupNumber != null }

        // Build standings map by team
        val standingsMap = mutableMapOf<String, GroupStandingBuilder>()

        // Initialize from existing standings to get group numbers
        val existingStandings = repository.getStandings(bracket.id)
        for (standing in existingStandings) {
            val teamId = standing.teamId ?: continue
            val groupNumber = standing.groupNumber ?: continue
            standingsMap[teamId] = GroupStandingBuilder(
                teamId = teamId,
                groupNumber = groupNumber
            )
        }

        // If no standings exist, initialize from match participants
        if (standingsMap.isEmpty() && groupMatches.isNotEmpty()) {
            for (match in groupMatches) {
                val groupNumber = match.groupNumber ?: continue
                match.team1Id?.let { teamId ->
                    if (teamId !in standingsMap) {
                        standingsMap[teamId] = GroupStandingBuilder(teamId = teamId, groupNumber = groupNumber)
                    }
                }
                match.team2Id?.let { teamId ->
                    if (teamId !in standingsMap) {
                        standingsMap[teamId] = GroupStandingBuilder(teamId = teamId, groupNumber = groupNumber)
                    }
                }
            }
        }

        // Process completed group matches
        for (match in groupMatches.filter { it.status == "completed" }) {
            val team1Id = match.team1Id ?: continue
            val team2Id = match.team2Id ?: continue

            // Parse set scores
            val setScores = match.setScores?.let {
                try { json.decodeFromJsonElement<List<SetScore>>(it) }
                catch (e: Exception) { null }
            } ?: emptyList()

            val team1Games = setScores.sumOf { it.team1 }
            val team2Games = setScores.sumOf { it.team2 }

            // Update team1 stats
            standingsMap[team1Id]?.apply {
                matchesPlayed++
                gamesWon += team1Games
                gamesLost += team2Games
                if (match.winnerTeam == 1) {
                    matchesWon++
                    totalPoints += 3  // 3 points for win
                } else {
                    matchesLost++
                }
            }

            // Update team2 stats
            standingsMap[team2Id]?.apply {
                matchesPlayed++
                gamesWon += team2Games
                gamesLost += team1Games
                if (match.winnerTeam == 2) {
                    matchesWon++
                    totalPoints += 3
                } else {
                    matchesLost++
                }
            }
        }

        // Sort by group, then by points, then by game difference
        val sortedStandings = standingsMap.values
            .groupBy { it.groupNumber }
            .flatMap { (_, groupTeams) ->
                groupTeams.sortedWith(
                    compareByDescending<GroupStandingBuilder> { it.totalPoints }
                        .thenByDescending { it.gamesWon - it.gamesLost }
                        .thenByDescending { it.gamesWon }
                ).mapIndexed { index, builder ->
                    StandingInput(
                        bracketId = bracket.id,
                        teamId = builder.teamId,
                        playerId = null,
                        position = index + 1,
                        totalPoints = builder.totalPoints,
                        matchesPlayed = builder.matchesPlayed,
                        matchesWon = builder.matchesWon,
                        matchesLost = builder.matchesLost,
                        gamesWon = builder.gamesWon,
                        gamesLost = builder.gamesLost,
                        pointDifference = builder.gamesWon - builder.gamesLost,
                        roundReached = null,
                        groupNumber = builder.groupNumber
                    )
                }
            }

        // Persist standings
        repository.upsertStandings(sortedStandings)

        val savedStandings = repository.getStandings(bracket.id)
        return Result.success(StandingsResponse(
            bracketId = bracket.id,
            tournamentId = tournamentId,
            categoryId = categoryId,
            standings = savedStandings
        ))
    }

    /**
     * Generate knockout phase from group stage results.
     * Takes top N teams from each group based on standings.
     */
    suspend fun generateKnockoutFromGroups(
        tournamentId: String,
        categoryId: Int
    ): Result<BracketWithMatchesResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches
        val standings = bracketWithMatches.standings

        // Parse config
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        } ?: return Result.failure(IllegalStateException("Invalid bracket config"))

        // Check if knockout already exists
        val knockoutMatches = matches.filter { it.groupNumber == null }
        if (knockoutMatches.isNotEmpty()) {
            return Result.failure(IllegalStateException("Knockout phase already generated"))
        }

        // Check all group matches are complete
        val groupMatches = matches.filter { it.groupNumber != null }
        val incompleteCount = groupMatches.count { it.status != "completed" }
        if (incompleteCount > 0) {
            return Result.failure(IllegalArgumentException(
                "Cannot generate knockout: $incompleteCount group matches still incomplete"
            ))
        }

        // Get advancing teams from each group
        var groupStandings = standings
            .filter { it.groupNumber != null }
            .groupBy { it.groupNumber!! }

        // If standings don't have group numbers, recalculate them first
        if (groupStandings.isEmpty() && groupMatches.isNotEmpty()) {
            val recalcResult = calculateGroupStandings(tournamentId, categoryId)
            if (recalcResult.isSuccess) {
                val recalcStandings = recalcResult.getOrNull()?.standings ?: emptyList()
                groupStandings = recalcStandings
                    .filter { it.groupNumber != null }
                    .groupBy { it.groupNumber!! }
            }
        }

        val advancingTeams = mutableListOf<TeamSeed>()
        val teamGroupMap = mutableMapOf<String, Int>() // teamId -> groupNumber
        var seedCounter = 1

        // Collect advancing teams by position, tracking group origin
        for (position in 1..config.advancingPerGroup) {
            for (groupNum in 1..config.groupCount) {
                val groupTeams = groupStandings[groupNum]?.sortedBy { it.position } ?: emptyList()
                val teamAtPosition = groupTeams.getOrNull(position - 1)
                val teamId = teamAtPosition?.teamId
                if (teamId == null) {
                    return Result.failure(IllegalStateException(
                        "No team at position $position in group $groupNum"
                    ))
                }
                advancingTeams.add(TeamSeed(teamId, seedCounter++))
                teamGroupMap[teamId] = groupNum
            }
        }

        // Add wildcards (best runners-up) if configured
        val wildcardCount = config.wildcardCount
        if (wildcardCount > 0) {
            val wildcardPosition = config.advancingPerGroup + 1

            val wildcardCandidates = mutableListOf<StandingEntry>()
            for (groupNum in 1..config.groupCount) {
                val groupTeams = groupStandings[groupNum]?.sortedBy { it.position } ?: emptyList()
                val candidate = groupTeams.getOrNull(wildcardPosition - 1)
                if (candidate != null) {
                    wildcardCandidates.add(candidate)
                }
            }

            val sortedWildcards = wildcardCandidates.sortedWith(
                compareByDescending<StandingEntry> { it.totalPoints }
                    .thenByDescending { it.pointDifference }
                    .thenByDescending { it.gamesWon }
            )

            val selectedWildcards = sortedWildcards.take(wildcardCount)

            for (wildcard in selectedWildcards) {
                val teamId = wildcard.teamId ?: continue
                advancingTeams.add(TeamSeed(teamId, seedCounter++))
                teamGroupMap[teamId] = wildcard.groupNumber ?: 0
            }
        }

        // Cross-group seeding: ensure no same-group matchups in first round
        val seededTeams = resolveSameGroupConflicts(advancingTeams, teamGroupMap)

        // Get current max match number
        val maxMatchNumber = matches.maxOfOrNull { it.matchNumber } ?: 0

        // Generate knockout bracket
        val knockoutMatchesGenerated = generateKnockoutMatches(seededTeams)
            .map { it.copy(matchNumber = it.matchNumber + maxMatchNumber) }

        // Create matches
        val createdMatches = repository.createMatches(bracket.id, knockoutMatchesGenerated)
        if (createdMatches.isEmpty() && knockoutMatchesGenerated.isNotEmpty()) {
            return Result.failure(IllegalStateException("Failed to create knockout matches"))
        }

        // Build match number to UUID mapping
        val matchNumberToId = createdMatches.associate { it.matchNumber to it.id }

        // Update next_match_id references
        for (generated in knockoutMatchesGenerated) {
            if (generated.nextMatchNumber != null && generated.nextMatchPosition != null) {
                val adjustedNextMatchNumber = generated.nextMatchNumber + maxMatchNumber
                val matchId = matchNumberToId[generated.matchNumber] ?: continue
                val nextMatchId = matchNumberToId[adjustedNextMatchNumber] ?: continue
                repository.updateMatchNextMatchId(matchId, nextMatchId, generated.nextMatchPosition)
            }
        }

        // Auto-advance BYE matches
        for (generated in knockoutMatchesGenerated) {
            if (!generated.isBye) continue
            val matchId = matchNumberToId[generated.matchNumber] ?: continue
            val advancingTeamId = generated.team1Id ?: generated.team2Id ?: continue
            val nextMatchNumber = generated.nextMatchNumber?.let { it + maxMatchNumber } ?: continue
            val nextMatchId = matchNumberToId[nextMatchNumber] ?: continue
            val position = generated.nextMatchPosition ?: continue
            val field = if (position == 1) "team1_id" else "team2_id"
            repository.updateMatchField(nextMatchId, field, advancingTeamId)
        }

        // Return complete bracket
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch updated bracket"))

        return Result.success(result)
    }

    /**
     * Delete knockout phase matches (keeps group stage intact).
     * Allows regenerating knockout with different rules or fixing errors.
     */
    suspend fun deleteKnockoutPhase(
        tournamentId: String,
        categoryId: Int
    ): Result<Unit> {
        // Use transactional RPC — clears FK refs + deletes matches in a single DB transaction
        return repository.deleteKnockoutPhaseRpc(tournamentId, categoryId).map { }
    }

    /**
     * Clear all group results, resetting matches to pending and standings to zeros.
     * Blocked if knockout phase has completed results.
     */
    suspend fun clearGroupResults(tournamentId: String, categoryId: Int): Result<Unit> {
        return repository.clearGroupResultsRpc(tournamentId, categoryId).map { }
    }

    /**
     * Swap two teams between groups (before group stage starts).
     */
    suspend fun swapTeamsInGroups(
        tournamentId: String,
        categoryId: Int,
        team1Id: String,
        team2Id: String
    ): Result<GroupsStateResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Check if the specific teams being swapped have played matches
        val team1HasPlayedMatches = matches.any {
            (it.team1Id == team1Id || it.team2Id == team1Id) &&
            it.status in listOf("in_progress", "completed")
        }
        val team2HasPlayedMatches = matches.any {
            (it.team1Id == team2Id || it.team2Id == team2Id) &&
            it.status in listOf("in_progress", "completed")
        }

        if (team1HasPlayedMatches) {
            return Result.failure(IllegalStateException(
                "El primer equipo ya tiene partidos jugados y no puede ser movido"
            ))
        }
        if (team2HasPlayedMatches) {
            return Result.failure(IllegalStateException(
                "El segundo equipo ya tiene partidos jugados y no puede ser movido"
            ))
        }

        // Find which groups these teams are in
        val team1Matches = matches.filter { it.team1Id == team1Id || it.team2Id == team1Id }
        val team2Matches = matches.filter { it.team1Id == team2Id || it.team2Id == team2Id }

        val team1Group = team1Matches.firstOrNull()?.groupNumber
            ?: return Result.failure(IllegalArgumentException("Team 1 not found in any group"))
        val team2Group = team2Matches.firstOrNull()?.groupNumber
            ?: return Result.failure(IllegalArgumentException("Team 2 not found in any group"))

        if (team1Group == team2Group) {
            return Result.failure(IllegalArgumentException("Teams are already in the same group"))
        }

        // Swap teams in all affected matches
        for (match in team1Matches) {
            val newTeam1Id = if (match.team1Id == team1Id) team2Id else match.team1Id
            val newTeam2Id = if (match.team2Id == team1Id) team2Id else match.team2Id
            repository.updateMatchTeams(match.id, newTeam1Id, newTeam2Id, team2Group)
        }

        for (match in team2Matches) {
            val newTeam1Id = if (match.team1Id == team2Id) team1Id else match.team1Id
            val newTeam2Id = if (match.team2Id == team2Id) team1Id else match.team2Id
            repository.updateMatchTeams(match.id, newTeam1Id, newTeam2Id, team1Group)
        }

        // Update standings group numbers
        repository.updateStandingGroupNumber(bracket.id, team1Id, team2Group)
        repository.updateStandingGroupNumber(bracket.id, team2Id, team1Group)

        return getGroupsState(tournamentId, categoryId)
    }

    /**
     * Helper class for building group standings
     */
    private data class GroupStandingBuilder(
        val teamId: String,
        val groupNumber: Int,
        var matchesPlayed: Int = 0,
        var matchesWon: Int = 0,
        var matchesLost: Int = 0,
        var gamesWon: Int = 0,
        var gamesLost: Int = 0,
        var totalPoints: Int = 0
    )

    /**
     * Generate round-robin matches for a group.
     * n teams = n*(n-1)/2 matches
     */
    private fun generateRoundRobinMatches(
        teamIds: List<String>,
        groupNumber: Int,
        startMatchNumber: Int
    ): List<GeneratedMatch> {
        val matches = mutableListOf<GeneratedMatch>()
        var matchNumber = startMatchNumber
        val n = teamIds.size

        // Generate all pairings using round-robin algorithm
        // Each team plays every other team exactly once
        for (i in 0 until n) {
            for (j in (i + 1) until n) {
                matches.add(GeneratedMatch(
                    roundNumber = 1,  // All group matches in round 1
                    matchNumber = matchNumber++,
                    roundName = "Grupo ${('A' + groupNumber - 1)}",
                    team1Id = teamIds[i],
                    team2Id = teamIds[j],
                    isBye = false,
                    status = "pending",
                    nextMatchNumber = null,
                    nextMatchPosition = null,
                    groupNumber = groupNumber
                ))
            }
        }

        return matches
    }

    // ============ Bracket Generation Algorithm ============

    /**
     * Generate knockout matches from seeded teams.
     * Implements proper seeding placement so #1 meets #2 only in final.
     */
    private fun generateKnockoutMatches(teams: List<TeamSeed>): List<GeneratedMatch> {
        val teamCount = teams.size
        val bracketSize = nextPowerOfTwo(teamCount)
        val totalRounds = kotlin.math.log2(bracketSize.toDouble()).toInt()

        // Generate seed positions for bracket
        val seedPositions = generateSeedPositions(bracketSize)

        // Map teams to positions (with BYEs for missing seeds)
        val positionToTeam = mutableMapOf<Int, String?>()
        teams.forEach { team ->
            val position = seedPositions.indexOf(team.seed)
            if (position >= 0) {
                positionToTeam[position] = team.teamId
            }
        }
        // Fill remaining positions with null (BYEs)
        for (i in 0 until bracketSize) {
            if (!positionToTeam.containsKey(i)) {
                positionToTeam[i] = null
            }
        }

        val matches = mutableListOf<GeneratedMatch>()
        var matchNumber = 1

        // Track which match each position feeds into for next round linking
        val positionToMatchNumber = mutableMapOf<Int, Int>()

        // Generate round 1 matches
        val round1MatchCount = bracketSize / 2
        for (i in 0 until round1MatchCount) {
            val pos1 = i * 2
            val pos2 = i * 2 + 1
            val team1 = positionToTeam[pos1]
            val team2 = positionToTeam[pos2]

            val isBye = team1 == null || team2 == null
            val status = when {
                isBye -> "bye"
                else -> "pending"
            }

            val roundName = getRoundName(1, totalRounds)

            matches.add(
                GeneratedMatch(
                    roundNumber = 1,
                    matchNumber = matchNumber,
                    roundName = roundName,
                    team1Id = team1,
                    team2Id = team2,
                    isBye = isBye,
                    status = status,
                    nextMatchNumber = null,  // Will be set after all matches created
                    nextMatchPosition = null
                )
            )

            // Track which next-round position this match feeds
            positionToMatchNumber[i] = matchNumber
            matchNumber++
        }

        // Generate subsequent rounds
        var currentRoundMatches = round1MatchCount
        for (round in 2..totalRounds) {
            val matchesInRound = currentRoundMatches / 2
            val prevRoundFirstMatch = matchNumber - currentRoundMatches

            for (i in 0 until matchesInRound) {
                val feedMatch1 = prevRoundFirstMatch + (i * 2)
                val feedMatch2 = prevRoundFirstMatch + (i * 2) + 1

                matches.add(
                    GeneratedMatch(
                        roundNumber = round,
                        matchNumber = matchNumber,
                        roundName = getRoundName(round, totalRounds),
                        team1Id = null,  // To be filled by match results
                        team2Id = null,
                        isBye = false,
                        status = "pending",
                        nextMatchNumber = null,
                        nextMatchPosition = null
                    )
                )

                // Update feeding matches with next_match reference
                val feedIndex1 = matches.indexOfFirst { it.matchNumber == feedMatch1 }
                val feedIndex2 = matches.indexOfFirst { it.matchNumber == feedMatch2 }

                if (feedIndex1 >= 0) {
                    matches[feedIndex1] = matches[feedIndex1].copy(
                        nextMatchNumber = matchNumber,
                        nextMatchPosition = 1
                    )
                }
                if (feedIndex2 >= 0) {
                    matches[feedIndex2] = matches[feedIndex2].copy(
                        nextMatchNumber = matchNumber,
                        nextMatchPosition = 2
                    )
                }

                matchNumber++
            }

            currentRoundMatches = matchesInRound
        }

        return matches
    }

    /**
     * Calculate next power of 2 for bracket size
     */
    private fun nextPowerOfTwo(n: Int): Int {
        var power = 1
        while (power < n) power *= 2
        return power
    }

    /**
     * Generate seed positions for bracket.
     * This ensures top seeds meet only in later rounds.
     * For size 8: [1, 8, 5, 4, 3, 6, 7, 2]
     * So #1 is at position 0, #2 at position 7 (opposite ends)
     */
    private fun generateSeedPositions(bracketSize: Int): List<Int> {
        if (bracketSize == 2) return listOf(1, 2)

        val halfSize = bracketSize / 2
        val topHalf = generateSeedPositions(halfSize)
        val bottomHalf = topHalf.map { bracketSize + 1 - it }

        // Interleave: top and bottom pairs
        return topHalf.zip(bottomHalf).flatMap { (a, b) -> listOf(a, b) }
    }

    /**
     * Get round name based on round number and total rounds
     */
    private fun getRoundName(round: Int, totalRounds: Int): String {
        val roundsFromEnd = totalRounds - round + 1
        return when (roundsFromEnd) {
            1 -> "Final"
            2 -> "Semifinals"
            3 -> "Quarterfinals"
            else -> "Round $round"
        }
    }

    /**
     * Resolve same-group conflicts in knockout seeding.
     * Ensures no two teams from the same group face each other in the first round.
     * Uses bracket seed positions to determine matchups, then swaps seeds to fix conflicts.
     */
    private fun resolveSameGroupConflicts(
        teams: List<TeamSeed>,
        teamGroupMap: Map<String, Int>
    ): List<TeamSeed> {
        if (teams.size <= 1) return teams

        val bracketSize = nextPowerOfTwo(teams.size)
        val seedPositions = generateSeedPositions(bracketSize)

        // Build mutable seed-to-team mapping
        val seedToTeam = mutableMapOf<Int, TeamSeed>()
        teams.forEach { seedToTeam[it.seed] = it }

        // Determine first-round match pairs (which seeds face each other)
        val matchPairs = (0 until bracketSize step 2).map { i ->
            seedPositions[i] to seedPositions[i + 1]
        }

        // Check each match pair for same-group conflicts
        for (i in matchPairs.indices) {
            val (s1, s2) = matchPairs[i]
            val t1 = seedToTeam[s1] ?: continue
            val t2 = seedToTeam[s2] ?: continue

            val g1 = teamGroupMap[t1.teamId]
            val g2 = teamGroupMap[t2.teamId]

            if (g1 != null && g1 == g2) {
                // Conflict: find another match to swap with
                var resolved = false
                for (j in matchPairs.indices) {
                    if (j == i || resolved) continue
                    val (os1, os2) = matchPairs[j]

                    // Try swapping t2 with the second team from match j
                    val ot2 = seedToTeam[os2]
                    if (ot2 != null) {
                        val og2 = teamGroupMap[ot2.teamId]
                        val og1 = teamGroupMap[seedToTeam[os1]?.teamId ?: ""]
                        // Swap is valid if: t1 vs ot2 are different groups AND ot1 vs t2 are different groups
                        if (og2 != g1 && (og1 == null || og1 != g2)) {
                            // Swap seeds
                            val newT2 = TeamSeed(ot2.teamId, s2)
                            val newOt2 = TeamSeed(t2.teamId, os2)
                            seedToTeam[s2] = newT2
                            seedToTeam[os2] = newOt2
                            resolved = true
                            continue
                        }
                    }

                    // Try swapping t2 with the first team from match j
                    val ot1 = seedToTeam[os1]
                    if (ot1 != null) {
                        val og1 = teamGroupMap[ot1.teamId]
                        val og2j = teamGroupMap[seedToTeam[os2]?.teamId ?: ""]
                        if (og1 != g1 && (og2j == null || og2j != g2)) {
                            val newT2 = TeamSeed(ot1.teamId, s2)
                            val newOt1 = TeamSeed(t2.teamId, os1)
                            seedToTeam[s2] = newT2
                            seedToTeam[os1] = newOt1
                            resolved = true
                            continue
                        }
                    }
                }
            }
        }

        return seedToTeam.values.sortedBy { it.seed }
    }

    // ============ Status and Withdrawal ============

    /**
     * Update match status without changing score.
     * Useful for starting/pausing matches or marking as in_progress.
     */
    suspend fun updateMatchStatus(matchId: String, status: String): Result<MatchResponse> {
        return try {
            val updated = repository.updateMatchStatus(matchId, status)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update match schedule (court number and scheduled time).
     * Used by the scheduling UI to assign matches to courts/times.
     * Pass null values to clear the schedule.
     */
    suspend fun updateMatchSchedule(matchId: String, courtNumber: Int?, scheduledTime: String?): Result<MatchResponse> {
        return try {
            val updated = repository.updateMatchSchedule(matchId, courtNumber, scheduledTime)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============ Withdrawal ============

    /**
     * Withdraw a team from the tournament.
     * Marks all their pending/scheduled matches as forfeit and advances opponents.
     */
    suspend fun withdrawTeam(
        tournamentId: String,
        categoryId: Int,
        teamId: String,
        reason: String?,
        organizerId: String? = null
    ): Result<WithdrawTeamResponse> {
        return try {
            // Get bracket for this category
            val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
                ?: return Result.failure(IllegalArgumentException("Bracket not found"))

            val bracket = bracketWithMatches.bracket

            // Get all matches for this team
            val matches = repository.getMatchesForTeam(bracket.id, teamId)
            val pendingMatches = matches.filter { it.status in listOf("pending", "scheduled") }

            val forfeitedIds = mutableListOf<String>()

            for (match in pendingMatches) {
                // Determine winner (opponent gets the win)
                val winnerTeam = if (match.team1Id == teamId) 2 else 1

                // Mark as forfeit with winner
                repository.updateMatchForfeit(match.id, winnerTeam)
                forfeitedIds.add(match.id)

                // Advance winner to next match if exists
                if (match.nextMatchId != null) {
                    val winnerId = if (winnerTeam == 1) match.team1Id else match.team2Id
                    if (winnerId != null) {
                        repository.advanceToNextMatch(
                            match.id,
                            winnerId,
                            match.nextMatchId,
                            match.nextMatchPosition ?: 1
                        )
                    }
                }
            }

            auditLog.log("bracket", bracket.id, "withdraw", organizerId, mapOf(
                "team_id" to teamId,
                "reason" to (reason ?: ""),
                "forfeited_matches" to forfeitedIds.size.toString()
            ))

            Result.success(WithdrawTeamResponse(
                forfeitedMatches = forfeitedIds,
                message = "Team withdrawn. ${forfeitedIds.size} match(es) forfeited."
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
