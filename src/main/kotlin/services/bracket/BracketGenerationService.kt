package services.bracket

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import models.bracket.AssignGroupsRequest
import models.bracket.BracketResponse
import models.bracket.BracketWithMatchesResponse
import models.bracket.GeneratedMatch
import models.bracket.GroupAssignment
import models.bracket.GroupsKnockoutConfig
import models.bracket.GroupState
import models.bracket.GroupsStateResponse
import models.bracket.MatchResponse
import models.bracket.StandingEntry
import models.bracket.StandingInput
import models.bracket.TeamSeed
import repositories.bracket.BracketAuditRepository
import repositories.bracket.BracketRepository

/**
 * Service for bracket generation algorithms and group management.
 */
class BracketGenerationService(
    private val repository: BracketRepository,
    private val json: Json,
    private val auditLog: BracketAuditRepository,
    private val standingsService: BracketStandingsService
) {
    companion object {
        const val MAX_TEAMS_PER_BRACKET = 128
        const val MAX_GROUPS = 16
        const val MAX_SETS_PER_MATCH = 5
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
        if (teamIds.size > MAX_TEAMS_PER_BRACKET) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_TEAMS_PER_BRACKET teams per bracket"))
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

        // Auto-advance bye matches: mark as completed and advance winner to next round
        processByeMatches(createdMatches, matchNumberToId)

        // Fetch complete bracket with updated matches
        val result = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalStateException("Failed to fetch created bracket"))

        return Result.success(result)
    }

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
        if (config.groupCount > MAX_GROUPS) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_GROUPS groups allowed"))
        }
        val totalTeams = groups.sumOf { it.teamIds.size }
        if (totalTeams > MAX_TEAMS_PER_BRACKET) {
            return Result.failure(IllegalArgumentException("Maximum $MAX_TEAMS_PER_BRACKET teams per bracket"))
        }

        // Validate match format config if provided
        config.matchFormat?.let { format ->
            if (format.pointsPerSet != null && format.pointsPerSet <= 0) {
                return Result.failure(IllegalArgumentException("pointsPerSet must be greater than 0"))
            }
            if (format.gamesPerSet != null && format.gamesPerSet <= 0) {
                return Result.failure(IllegalArgumentException("gamesPerSet must be greater than 0"))
            }
            if (format.sets <= 0 || format.sets > MAX_SETS_PER_MATCH) {
                return Result.failure(IllegalArgumentException("sets must be between 1 and $MAX_SETS_PER_MATCH"))
            }
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
     * Generate group stage with server-side group computation.
     * Backend computes group formation + snake seeding from team IDs and config.
     */
    suspend fun generateGroupStageAuto(
        tournamentId: String,
        categoryId: Int,
        teamIds: List<String>,
        config: GroupsKnockoutConfig
    ): Result<BracketWithMatchesResponse> {
        // Compute group sizes from config or auto-detect
        val (_, groupSizes) = if (config.groupCount > 0 && config.teamsPerGroup > 0) {
            // Use explicit config
            val sizes = List(config.groupCount) { config.teamsPerGroup }
            config.groupCount to sizes
        } else {
            computeGroupFormation(teamIds.size)
        }

        if (groupSizes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Cannot form groups from ${teamIds.size} teams"))
        }

        // Snake-seed teams into groups
        val groupedTeams = snakeSeedTeamsWithSizes(teamIds, groupSizes)

        // Build AssignGroupsRequest
        val groups = groupedTeams.mapIndexed { index, teams ->
            GroupAssignment(groupNumber = index + 1, teamIds = teams)
        }

        val actualConfig = config.copy(
            groupCount = groupSizes.size,
            teamsPerGroup = groupSizes.maxOrNull() ?: 0
        )

        val request = AssignGroupsRequest(groups = groups, config = actualConfig)
        return generateGroupStage(tournamentId, categoryId, request)
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
        val phase = when {
            knockoutGenerated -> "knockout"
            else -> "groups"
        }

        // Build group states
        val groupsList = (1..config.groupCount).map { groupNum ->
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
            groups = groupsList,
            phase = phase,
            knockoutGenerated = knockoutGenerated
        ))
    }

    /**
     * Swap two teams between groups (before group stage starts).
     */
    suspend fun swapTeamsInGroups(
        tournamentId: String,
        categoryId: Int,
        team1Id: String,
        team2Id: String,
        organizerId: String? = null
    ): Result<GroupsStateResponse> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Atomically swap teams in a single DB transaction
        val swapResult = repository.swapTeamsInGroupsAtomic(bracket.id, team1Id, team2Id)
        if (swapResult.isFailure) {
            return Result.failure(swapResult.exceptionOrNull()!!)
        }

        auditLog.log("bracket", bracket.id, "swap_teams", organizerId, mapOf(
            "team1_id" to team1Id, "team2_id" to team2Id
        ))

        // Recalculate standings if any matches have been played
        val hasPlayedMatches = matches.any { it.status in listOf("completed", "forfeit") }
        if (hasPlayedMatches) {
            standingsService.calculateGroupStandings(tournamentId, categoryId)
        }

        return getGroupsState(tournamentId, categoryId)
    }

    /**
     * Generate knockout phase from group stage results.
     * Takes top N teams from each group based on standings.
     */
    suspend fun generateKnockoutFromGroups(
        tournamentId: String,
        categoryId: Int,
        organizerId: String? = null
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
            val recalcResult = standingsService.calculateGroupStandings(tournamentId, categoryId)
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

        // Collect advancing teams by position, sorted by performance within each tier
        for (position in 1..config.advancingPerGroup) {
            val teamsAtPosition = mutableListOf<Pair<StandingEntry, Int>>() // (standing, groupNum)
            for (groupNum in 1..config.groupCount) {
                val groupTeams = groupStandings[groupNum]?.sortedBy { it.position } ?: emptyList()
                val teamAtPosition = groupTeams.getOrNull(position - 1)
                if (teamAtPosition?.teamId == null) {
                    return Result.failure(IllegalStateException(
                        "No team at position $position in group $groupNum"
                    ))
                }
                teamsAtPosition.add(teamAtPosition to groupNum)
            }

            // Within same position tier, sort by performance (points, point diff, games won)
            val sorted = teamsAtPosition.sortedWith(
                compareByDescending<Pair<StandingEntry, Int>> { it.first.totalPoints }
                    .thenByDescending { it.first.pointDifference }
                    .thenByDescending { it.first.gamesWon }
            )

            for ((standing, groupNum) in sorted) {
                val teamId = standing.teamId!!
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

        // Cross-group seeding: separate same-group teams across bracket halves/quarters
        val seededTeams = placeTeamsAntiRematch(advancingTeams, teamGroupMap)

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

        // Auto-advance bye matches
        processByeMatches(createdMatches, matchNumberToId)

        auditLog.log("bracket", bracket.id, "generate_knockout", organizerId, mapOf(
            "knockout_matches" to createdMatches.size.toString(),
            "advancing_teams" to seededTeams.size.toString()
        ))

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
        categoryId: Int,
        organizerId: String? = null
    ): Result<Unit> {
        val bracketWithMatches = repository.getBracketWithMatches(tournamentId, categoryId)
            ?: return Result.failure(IllegalArgumentException("Bracket not found"))

        val bracket = bracketWithMatches.bracket
        val matches = bracketWithMatches.matches

        // Find all knockout matches (matches with groupNumber = null)
        val knockoutMatches = matches.filter { it.groupNumber == null }

        if (knockoutMatches.isEmpty()) {
            return Result.failure(IllegalArgumentException("No knockout phase found to delete"))
        }

        // Check if any knockout match has been played
        val playedKnockoutMatches = knockoutMatches.filter { it.status in listOf("in_progress", "completed") }
        if (playedKnockoutMatches.isNotEmpty()) {
            return Result.failure(IllegalStateException(
                "Cannot delete knockout phase: ${playedKnockoutMatches.size} match(es) already started or completed"
            ))
        }

        // Delete all knockout matches
        val deletedCount = repository.deleteMatchesByIds(knockoutMatches.map { it.id })

        if (deletedCount != knockoutMatches.size) {
            return Result.failure(IllegalStateException("Failed to delete all knockout matches"))
        }

        auditLog.log("bracket", bracket.id, "delete_knockout", organizerId, mapOf(
            "deleted_matches" to deletedCount.toString()
        ))

        return Result.success(Unit)
    }

    // ============ Private Algorithm Helpers ============

    /**
     * Compute group formation: how many groups and their sizes.
     * Uses groups of 3 and 4 to minimize variance.
     */
    private fun computeGroupFormation(teamCount: Int): Pair<Int, List<Int>> {
        if (teamCount < 4) return 0 to emptyList()
        if (teamCount == 4) return 1 to listOf(4)
        if (teamCount == 5) return 1 to listOf(5)

        val remainder = teamCount % 3
        val groupsOf4 = when (remainder) { 0 -> 0; 1 -> 1; else -> 2 }
        val groupsOf3 = (teamCount - groupsOf4 * 4) / 3

        val sizes = List(groupsOf3) { 3 } + List(groupsOf4) { 4 }
        return (groupsOf3 + groupsOf4) to sizes
    }

    /**
     * Snake-seed teams into groups respecting exact group sizes.
     * Serpentine pattern alternates L→R and R→L per row.
     */
    private fun snakeSeedTeamsWithSizes(teamIds: List<String>, groupSizes: List<Int>): List<List<String>> {
        val groupCount = groupSizes.size
        if (groupCount < 1 || teamIds.isEmpty()) return emptyList()

        val groups = List(groupCount) { mutableListOf<String>() }
        var teamIndex = 0
        var row = 0

        while (teamIndex < teamIds.size) {
            val indices = (0 until groupCount).toList()
            val ordered = if (row % 2 == 0) indices else indices.reversed()

            for (gi in ordered) {
                if (teamIndex >= teamIds.size) break
                if (groups[gi].size < groupSizes[gi]) {
                    groups[gi].add(teamIds[teamIndex])
                    teamIndex++
                }
            }
            row++
        }

        return groups
    }

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

    /**
     * Process bye matches after bracket generation:
     * marks them as completed and advances the bye winner to the next round.
     */
    private suspend fun processByeMatches(
        createdMatches: List<MatchResponse>,
        matchNumberToId: Map<Int, String>
    ) {
        val byeMatches = createdMatches.filter { it.isBye && it.status == "bye" }

        for (byeMatch in byeMatches) {
            // Determine the winner (the non-null team)
            val winnerTeamId = byeMatch.team1Id ?: byeMatch.team2Id ?: continue
            val winnerTeam = if (byeMatch.team1Id != null) 1 else 2

            // Mark bye match as completed with the bye recipient as winner
            repository.updateMatchScore(
                matchId = byeMatch.id,
                scoreTeam1 = 0,
                scoreTeam2 = 0,
                setScores = emptyList(),
                winnerTeam = winnerTeam
            )

            // Advance winner to next match (next_match_id is set by this point)
            // Re-fetch the match to get the updated next_match_id
            val updatedMatch = repository.getMatch(byeMatch.id) ?: continue
            if (updatedMatch.nextMatchId != null) {
                repository.advanceWinner(byeMatch.id, winnerTeamId)
            }
        }
    }

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
            2 -> "Semifinales"
            3 -> "Cuartos de final"
            4 -> "Octavos de final"
            5 -> "Dieciseisavos"
            else -> "Ronda $round"
        }
    }

    /**
     * Place teams in the knockout bracket ensuring teams from the same group
     * meet as late as possible (anti-rematch).
     */
    private fun placeTeamsAntiRematch(
        teams: List<TeamSeed>,
        teamGroupMap: Map<String, Int>
    ): List<TeamSeed> {
        if (teams.size <= 1) return teams

        val bracketSize = nextPowerOfTwo(teams.size)
        val seedPositions = generateSeedPositions(bracketSize)

        // Map each seed position to its bracket slot index (0-based)
        val seedToSlot = mutableMapOf<Int, Int>()
        seedPositions.forEachIndexed { slot, seed -> seedToSlot[seed] = slot }

        // Build section assignments
        fun slotHalf(slot: Int): Int = slot / (bracketSize / 2)
        fun slotQuarter(slot: Int): Int = if (bracketSize >= 4) slot / (bracketSize / 4) else slot / 2

        // Group teams by origin group
        val teamsByGroup = mutableMapOf<Int, MutableList<TeamSeed>>()
        for (team in teams) {
            val group = teamGroupMap[team.teamId] ?: continue
            teamsByGroup.getOrPut(group) { mutableListOf() }.add(team)
        }

        // Build mutable seed-to-team mapping
        val seedToTeam = mutableMapOf<Int, TeamSeed>()
        teams.forEach { seedToTeam[it.seed] = it }

        // Sort groups by size descending (most constrained first)
        val sortedGroups = teamsByGroup.entries.sortedByDescending { it.value.size }

        // Track which sections are taken by which groups
        val halfGroupCounts = mutableMapOf<Int, MutableMap<Int, Int>>()
        val quarterGroupCounts = mutableMapOf<Int, MutableMap<Int, Int>>()

        // Initialize counts from current placement
        for (team in teams) {
            val group = teamGroupMap[team.teamId] ?: continue
            val slot = seedToSlot[team.seed] ?: continue
            val half = slotHalf(slot)
            val quarter = slotQuarter(slot)
            halfGroupCounts.getOrPut(half) { mutableMapOf() }
                .merge(group, 1) { a, b -> a + b }
            quarterGroupCounts.getOrPut(quarter) { mutableMapOf() }
                .merge(group, 1) { a, b -> a + b }
        }

        // Try to resolve conflicts by swapping teams between positions
        for ((group, groupTeams) in sortedGroups) {
            if (groupTeams.size < 2) continue

            val teamsInGroup = groupTeams.sortedBy { it.seed }

            if (teamsInGroup.size == 2) {
                // Need to be in opposite halves
                val t1 = teamsInGroup[0]
                val t2 = teamsInGroup[1]
                val slot1 = seedToSlot[t1.seed] ?: continue
                val slot2 = seedToSlot[t2.seed] ?: continue

                if (slotHalf(slot1) == slotHalf(slot2)) {
                    val targetHalf = 1 - slotHalf(slot1)
                    val swapCandidate = findSwapCandidate(
                        t2, group, targetHalf,
                        seedToTeam, seedToSlot, teamGroupMap,
                        ::slotHalf, ::slotQuarter, halfGroupCounts
                    )
                    if (swapCandidate != null) {
                        performSwap(t2, swapCandidate, seedToTeam, seedToSlot,
                            seedPositions, halfGroupCounts, quarterGroupCounts,
                            teamGroupMap, ::slotHalf, ::slotQuarter)
                    }
                }
            } else {
                // 3+ teams: try to spread across quarters
                for (i in 1 until teamsInGroup.size) {
                    val t = teamsInGroup[i]
                    val tSlot = seedToSlot[t.seed] ?: continue
                    val tQuarter = slotQuarter(tSlot)

                    val conflict = (0 until i).any { j ->
                        val otherSlot = seedToSlot[teamsInGroup[j].seed] ?: -1
                        slotQuarter(otherSlot) == tQuarter
                    }

                    if (conflict) {
                        val usedQuarters = (0 until i).mapNotNull { j ->
                            seedToSlot[teamsInGroup[j].seed]?.let { slotQuarter(it) }
                        }.toSet()
                        val numQuarters = if (bracketSize >= 4) 4 else 2
                        val freeQuarter = (0 until numQuarters).firstOrNull { it !in usedQuarters }

                        if (freeQuarter != null) {
                            val candidate = seedToTeam.values
                                .filter { candidate ->
                                    val cSlot = seedToSlot[candidate.seed] ?: -1
                                    val cGroup = teamGroupMap[candidate.teamId]
                                    slotQuarter(cSlot) == freeQuarter &&
                                    cGroup != group &&
                                    !wouldCreateConflict(candidate, tSlot, teamGroupMap, seedToTeam,
                                        seedToSlot, ::slotHalf, ::slotQuarter)
                                }
                                .minByOrNull { it.seed }

                            if (candidate != null) {
                                performSwap(t, candidate, seedToTeam, seedToSlot,
                                    seedPositions, halfGroupCounts, quarterGroupCounts,
                                    teamGroupMap, ::slotHalf, ::slotQuarter)
                            }
                        }
                    }
                }
            }
        }

        // Final safety pass: resolve any remaining first-round conflicts
        resolveFirstRoundConflicts(seedToTeam, seedPositions, bracketSize, teamGroupMap, seedToSlot)

        return seedToTeam.values.sortedBy { it.seed }
    }

    /**
     * Find a swap candidate in the target half that won't create new conflicts.
     */
    private fun findSwapCandidate(
        teamToMove: TeamSeed,
        teamGroup: Int,
        targetHalf: Int,
        seedToTeam: Map<Int, TeamSeed>,
        seedToSlot: Map<Int, Int>,
        teamGroupMap: Map<String, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int,
        halfGroupCounts: Map<Int, Map<Int, Int>>
    ): TeamSeed? {
        return seedToTeam.values
            .filter { candidate ->
                val cSlot = seedToSlot[candidate.seed] ?: return@filter false
                val cGroup = teamGroupMap[candidate.teamId]
                slotHalf(cSlot) == targetHalf &&
                cGroup != teamGroup &&
                candidate.seed != teamToMove.seed
            }
            .minByOrNull { Math.abs(it.seed - teamToMove.seed) }
    }

    /**
     * Check if placing a candidate at a target slot would create a same-group
     * conflict with another team already in that half/quarter.
     */
    private fun wouldCreateConflict(
        candidate: TeamSeed,
        targetSlot: Int,
        teamGroupMap: Map<String, Int>,
        seedToTeam: Map<Int, TeamSeed>,
        seedToSlot: Map<Int, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int
    ): Boolean {
        val candidateGroup = teamGroupMap[candidate.teamId] ?: return false
        val targetQuarter = slotQuarter(targetSlot)

        return seedToTeam.values.any { other ->
            if (other.seed == candidate.seed) return@any false
            val otherGroup = teamGroupMap[other.teamId]
            val otherSlot = seedToSlot[other.seed] ?: return@any false
            otherGroup == candidateGroup && slotQuarter(otherSlot) == targetQuarter
        }
    }

    /**
     * Swap two teams' seeds and update tracking maps.
     */
    private fun performSwap(
        team1: TeamSeed,
        team2: TeamSeed,
        seedToTeam: MutableMap<Int, TeamSeed>,
        seedToSlot: MutableMap<Int, Int>,
        seedPositions: List<Int>,
        halfGroupCounts: MutableMap<Int, MutableMap<Int, Int>>,
        quarterGroupCounts: MutableMap<Int, MutableMap<Int, Int>>,
        teamGroupMap: Map<String, Int>,
        slotHalf: (Int) -> Int,
        slotQuarter: (Int) -> Int
    ) {
        val seed1 = team1.seed
        val seed2 = team2.seed

        val group1 = teamGroupMap[team1.teamId] ?: return
        val group2 = teamGroupMap[team2.teamId] ?: return

        // Remove old counts
        val oldSlot1 = seedToSlot[seed1] ?: return
        val oldSlot2 = seedToSlot[seed2] ?: return
        halfGroupCounts[slotHalf(oldSlot1)]?.merge(group1, -1) { a, b -> a + b }
        halfGroupCounts[slotHalf(oldSlot2)]?.merge(group2, -1) { a, b -> a + b }
        quarterGroupCounts[slotQuarter(oldSlot1)]?.merge(group1, -1) { a, b -> a + b }
        quarterGroupCounts[slotQuarter(oldSlot2)]?.merge(group2, -1) { a, b -> a + b }

        // Swap
        val newTeam1 = TeamSeed(team2.teamId, seed1)
        val newTeam2 = TeamSeed(team1.teamId, seed2)
        seedToTeam[seed1] = newTeam1
        seedToTeam[seed2] = newTeam2

        // Add new counts
        halfGroupCounts.getOrPut(slotHalf(oldSlot1)) { mutableMapOf() }
            .merge(group2, 1) { a, b -> a + b }
        halfGroupCounts.getOrPut(slotHalf(oldSlot2)) { mutableMapOf() }
            .merge(group1, 1) { a, b -> a + b }
        quarterGroupCounts.getOrPut(slotQuarter(oldSlot1)) { mutableMapOf() }
            .merge(group2, 1) { a, b -> a + b }
        quarterGroupCounts.getOrPut(slotQuarter(oldSlot2)) { mutableMapOf() }
            .merge(group1, 1) { a, b -> a + b }
    }

    /**
     * Final safety pass: resolve any remaining first-round same-group conflicts.
     */
    private fun resolveFirstRoundConflicts(
        seedToTeam: MutableMap<Int, TeamSeed>,
        seedPositions: List<Int>,
        bracketSize: Int,
        teamGroupMap: Map<String, Int>,
        seedToSlot: MutableMap<Int, Int>
    ) {
        val matchPairs = (0 until bracketSize step 2).map { i ->
            seedPositions[i] to seedPositions[i + 1]
        }

        for (i in matchPairs.indices) {
            val (s1, s2) = matchPairs[i]
            val t1 = seedToTeam[s1] ?: continue
            val t2 = seedToTeam[s2] ?: continue
            val g1 = teamGroupMap[t1.teamId]
            val g2 = teamGroupMap[t2.teamId]

            if (g1 != null && g1 == g2) {
                for (j in matchPairs.indices) {
                    if (j == i) continue
                    val (os1, os2) = matchPairs[j]

                    val ot2 = seedToTeam[os2]
                    if (ot2 != null) {
                        val og2 = teamGroupMap[ot2.teamId]
                        val og1 = teamGroupMap[seedToTeam[os1]?.teamId ?: ""]
                        if (og2 != g1 && (og1 == null || og1 != g2)) {
                            val newT2 = TeamSeed(ot2.teamId, s2)
                            val newOt2 = TeamSeed(t2.teamId, os2)
                            seedToTeam[s2] = newT2
                            seedToTeam[os2] = newOt2
                            break
                        }
                    }

                    val ot1 = seedToTeam[os1]
                    if (ot1 != null) {
                        val og1 = teamGroupMap[ot1.teamId]
                        val og2j = teamGroupMap[seedToTeam[os2]?.teamId ?: ""]
                        if (og1 != g1 && (og2j == null || og2j != g2)) {
                            val newT2 = TeamSeed(ot1.teamId, s2)
                            val newOt1 = TeamSeed(t2.teamId, os1)
                            seedToTeam[s2] = newT2
                            seedToTeam[os1] = newOt1
                            break
                        }
                    }
                }
            }
        }
    }
}
