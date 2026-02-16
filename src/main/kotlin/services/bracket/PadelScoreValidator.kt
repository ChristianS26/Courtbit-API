package services.bracket

import models.bracket.ScoreValidationResult
import models.bracket.SetScore

/**
 * Padel score validation supporting both classic and express formats.
 *
 * Classic format (FIP official rules):
 * - 6-0, 6-1, 6-2, 6-3, 6-4 (standard win)
 * - 7-5 (win from 5-5)
 * - 7-6 (tiebreak win at 6-6)
 *
 * Express format (points-based):
 * - First to N points wins (e.g., 8-0 through 8-7 for 8-point format)
 */
object PadelScoreValidator {

    /**
     * Generate valid winning scores for a given gamesPerSet (G).
     * G-0, G-1, ..., G-(G-2), (G+1)-(G-1), (G+1)-G
     */
    private fun getValidWinningScores(gamesPerSet: Int, allowTiebreak: Boolean = true): List<Pair<Int, Int>> {
        val scores = mutableListOf<Pair<Int, Int>>()
        // Regular wins: G-0 through G-(G-2)
        for (loser in 0..(gamesPerSet - 2)) {
            scores.add(gamesPerSet to loser)
        }
        if (allowTiebreak) {
            // Extended: (G+1)-(G-1)
            scores.add((gamesPerSet + 1) to (gamesPerSet - 1))
            // Tiebreak: (G+1)-G
            scores.add((gamesPerSet + 1) to gamesPerSet)
        }
        return scores
    }

    private fun getValidSetScores(gamesPerSet: Int, allowTiebreak: Boolean = true): Set<Pair<Int, Int>> = buildSet {
        getValidWinningScores(gamesPerSet, allowTiebreak).forEach { (a, b) ->
            add(a to b)  // Team 1 wins
            add(b to a)  // Team 2 wins
        }
    }

    fun isValidSetScore(team1Games: Int, team2Games: Int, gamesPerSet: Int = 6, allowTiebreak: Boolean = true): Boolean {
        return (team1Games to team2Games) in getValidSetScores(gamesPerSet, allowTiebreak)
    }

    private fun isTiebreakScore(team1: Int, team2: Int, gamesPerSet: Int): Boolean {
        return (team1 == gamesPerSet + 1 && team2 == gamesPerSet) ||
               (team1 == gamesPerSet && team2 == gamesPerSet + 1)
    }

    /**
     * Validate express format score (points-based).
     * One team must reach maxPoints, the other must be less than maxPoints.
     */
    fun validateExpressScore(setScores: List<SetScore>, maxPoints: Int, totalSets: Int): ScoreValidationResult {
        if (setScores.isEmpty()) {
            return ScoreValidationResult.Invalid("At least one set required")
        }
        if (setScores.size > totalSets) {
            return ScoreValidationResult.Invalid("Maximum $totalSets set(s) allowed")
        }

        var team1SetsWon = 0
        var team2SetsWon = 0
        val setsNeededToWin = (totalSets + 1) / 2  // Ceiling division for best-of-N

        for ((index, set) in setScores.withIndex()) {
            // Validate express score: one team must reach maxPoints exactly
            val team1Wins = set.team1 == maxPoints && set.team2 < maxPoints
            val team2Wins = set.team2 == maxPoints && set.team1 < maxPoints

            if (!team1Wins && !team2Wins) {
                // Check if it's a valid incomplete set or invalid score
                if (set.team1 > maxPoints || set.team2 > maxPoints) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1}: Maximum score is $maxPoints points"
                    )
                }
                if (set.team1 == maxPoints && set.team2 == maxPoints) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1}: Both teams cannot have $maxPoints points"
                    )
                }
                return ScoreValidationResult.Invalid(
                    "Set ${index + 1}: One team must reach $maxPoints points to win"
                )
            }

            // Determine set winner
            if (team1Wins) team1SetsWon++
            if (team2Wins) team2SetsWon++

            // Check if match is already decided
            if (team1SetsWon >= setsNeededToWin || team2SetsWon >= setsNeededToWin) {
                if (index < setScores.size - 1) {
                    return ScoreValidationResult.Invalid(
                        "Match already decided after set ${index + 1}, but ${setScores.size} sets provided"
                    )
                }
            }
        }

        // Verify match is complete
        return when {
            team1SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 1, setsWon = team1SetsWon to team2SetsWon)
            team2SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 2, setsWon = team1SetsWon to team2SetsWon)
            else -> ScoreValidationResult.Invalid("Match incomplete: need $setsNeededToWin set(s) to win (current: $team1SetsWon-$team2SetsWon)")
        }
    }

    /**
     * Validate classic format score (games-based).
     * @param gamesPerSet configurable games per set (default 6)
     * @param totalSets configurable total sets (default 3, best-of)
     */
    fun validateMatchScore(setScores: List<SetScore>, gamesPerSet: Int = 6, totalSets: Int = 3, allowTiebreak: Boolean = true): ScoreValidationResult {
        if (setScores.isEmpty()) {
            return ScoreValidationResult.Invalid("At least one set required")
        }
        if (setScores.size > totalSets) {
            return ScoreValidationResult.Invalid("Maximum $totalSets sets allowed")
        }

        val setsNeededToWin = (totalSets + 1) / 2
        var team1SetsWon = 0
        var team2SetsWon = 0

        for ((index, set) in setScores.withIndex()) {
            if (!isValidSetScore(set.team1, set.team2, gamesPerSet, allowTiebreak)) {
                val validScores = getValidWinningScores(gamesPerSet, allowTiebreak).joinToString(", ") { "${it.first}-${it.second}" }
                return ScoreValidationResult.Invalid(
                    "Invalid set ${index + 1} score: ${set.team1}-${set.team2}. " +
                    "Valid scores: $validScores"
                )
            }

            // Validate tiebreak if (G+1)-G
            if (isTiebreakScore(set.team1, set.team2, gamesPerSet)) {
                if (set.tiebreak == null) {
                    return ScoreValidationResult.Invalid(
                        "Set ${index + 1} is a tiebreak (${gamesPerSet + 1}-${gamesPerSet}), tiebreak score required"
                    )
                }
                // Tiebreak validation: first to 7 with 2-point lead
                val tb = set.tiebreak
                val winner = maxOf(tb.team1, tb.team2)
                val loser = minOf(tb.team1, tb.team2)
                if (winner < 7 || (winner - loser) < 2) {
                    return ScoreValidationResult.Invalid(
                        "Invalid tiebreak score: ${tb.team1}-${tb.team2}. " +
                        "Must be first to 7 with 2-point lead"
                    )
                }
            }

            // Determine set winner
            when {
                set.team1 > set.team2 -> team1SetsWon++
                set.team2 > set.team1 -> team2SetsWon++
            }

            // Check if match is already decided
            if (team1SetsWon >= setsNeededToWin || team2SetsWon >= setsNeededToWin) {
                if (index < setScores.size - 1) {
                    return ScoreValidationResult.Invalid(
                        "Match already decided after set ${index + 1}, but ${setScores.size} sets provided"
                    )
                }
            }
        }

        // Verify match is complete
        return when {
            team1SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 1, setsWon = team1SetsWon to team2SetsWon)
            team2SetsWon >= setsNeededToWin -> ScoreValidationResult.Valid(winner = 2, setsWon = team1SetsWon to team2SetsWon)
            else -> ScoreValidationResult.Invalid("Match incomplete: need $setsNeededToWin set(s) to win (current: $team1SetsWon-$team2SetsWon)")
        }
    }
}
