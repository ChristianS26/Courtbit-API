package services.bracket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import models.bracket.BracketResponse
import models.bracket.GroupsKnockoutConfig
import models.bracket.SetScore
import models.bracket.StandingEntry
import models.bracket.StandingInput
import models.bracket.StandingsResponse
import repositories.bracket.BracketRepository

/**
 * Service for bracket standings calculation (knockout and group stage).
 */
class BracketStandingsService(
    private val repository: BracketRepository,
    private val json: Json
) {
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

        // Parse config for configurable points
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        }
        val kp = config?.knockoutPoints ?: GroupsKnockoutConfig.KnockoutPointsConfig()

        // Calculate from completed and forfeited matches
        val completedMatches = matches.filter { it.status in listOf("completed", "forfeit") }

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
                builder.matchesLost == 0 && finalMatch != null -> "Campeón"
                builder.roundNameLost == "Final" -> "Finalista"
                builder.roundNameLost == "Semifinales" -> "Semifinalista"
                builder.roundNameLost == "Cuartos de final" -> "Cuartofinalista"
                else -> "Ronda ${builder.lastRoundLost ?: 1}"
            }

            val points = when (roundReached) {
                "Campeón" -> kp.winner
                "Finalista" -> kp.finalist
                "Semifinalista" -> kp.semiFinalist
                "Cuartofinalista" -> kp.quarterFinalist
                else -> kp.basePoints + ((builder.lastRoundLost ?: 1) * kp.perRoundBonus)
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

        // Parse config for configurable points
        val config = bracket.config?.let {
            try { json.decodeFromString<GroupsKnockoutConfig>(it.toString()) }
            catch (e: Exception) { null }
        }

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

        // Process completed and forfeited group matches
        for (match in groupMatches.filter { it.status in listOf("completed", "forfeit") }) {
            val team1Id = match.team1Id ?: continue
            val team2Id = match.team2Id ?: continue

            // Parse set scores
            val setScores = match.setScores?.let {
                try { json.decodeFromJsonElement<List<SetScore>>(it) }
                catch (e: Exception) { null }
            } ?: emptyList()

            val team1Games = setScores.sumOf { it.team1 }
            val team2Games = setScores.sumOf { it.team2 }

            val winPoints = config?.groupWinPoints ?: 3

            // Update team1 stats
            standingsMap[team1Id]?.apply {
                matchesPlayed++
                gamesWon += team1Games
                gamesLost += team2Games
                if (match.winnerTeam == 1) {
                    matchesWon++
                    totalPoints += winPoints
                } else {
                    matchesLost++
                }
                // H2H record vs team2
                headToHead.getOrPut(team2Id) { H2HRecord() }.apply {
                    gamesFor += team1Games
                    gamesAgainst += team2Games
                    if (match.winnerTeam == 1) wins++ else losses++
                }
            }

            // Update team2 stats
            standingsMap[team2Id]?.apply {
                matchesPlayed++
                gamesWon += team2Games
                gamesLost += team1Games
                if (match.winnerTeam == 2) {
                    matchesWon++
                    totalPoints += winPoints
                } else {
                    matchesLost++
                }
                // H2H record vs team1
                headToHead.getOrPut(team1Id) { H2HRecord() }.apply {
                    gamesFor += team2Games
                    gamesAgainst += team1Games
                    if (match.winnerTeam == 2) wins++ else losses++
                }
            }
        }

        // Sort by group, then by: points → H2H → game difference → games won
        val sortedStandings = standingsMap.values
            .groupBy { it.groupNumber }
            .flatMap { (_, groupTeams) ->
                groupTeams.sortedWith(
                    Comparator<GroupStandingBuilder> { a, b ->
                        // 1. Total points (descending)
                        val pointsDiff = b.totalPoints - a.totalPoints
                        if (pointsDiff != 0) return@Comparator pointsDiff

                        // 2. Head-to-head: team with more H2H wins ranks higher
                        val aWinsVsB = a.headToHead[b.teamId]?.wins ?: 0
                        val bWinsVsA = b.headToHead[a.teamId]?.wins ?: 0
                        if (aWinsVsB != bWinsVsA) return@Comparator bWinsVsA - aWinsVsB  // descending: more wins = smaller value

                        // 3. Game difference (descending)
                        val gdDiff = (b.gamesWon - b.gamesLost) - (a.gamesWon - a.gamesLost)
                        if (gdDiff != 0) return@Comparator gdDiff

                        // 4. Games won (descending)
                        b.gamesWon - a.gamesWon
                    }
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
        var totalPoints: Int = 0,
        val headToHead: MutableMap<String, H2HRecord> = mutableMapOf()
    )

    private data class H2HRecord(
        var wins: Int = 0,
        var losses: Int = 0,
        var gamesFor: Int = 0,
        var gamesAgainst: Int = 0
    )
}
