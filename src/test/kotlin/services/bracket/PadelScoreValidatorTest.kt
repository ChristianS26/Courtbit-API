package services.bracket

import models.bracket.ScoreValidationResult
import models.bracket.SetScore
import models.bracket.TiebreakScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PadelScoreValidatorTest {

    // ============ Classic Format (6 games per set, best of 3) ============

    @Test
    fun `classic - straight sets team1 wins`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 3), SetScore(6, 4))
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
        assertEquals(2 to 0, result.setsWon)
    }

    @Test
    fun `classic - straight sets team2 wins`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(2, 6), SetScore(4, 6))
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(2, result.winner)
        assertEquals(0 to 2, result.setsWon)
    }

    @Test
    fun `classic - three sets team1 wins`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 3), SetScore(4, 6), SetScore(6, 2))
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
        assertEquals(2 to 1, result.setsWon)
    }

    @Test
    fun `classic - tiebreak 7-6 with tiebreak score`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(
                SetScore(7, 6, tiebreak = TiebreakScore(7, 4)),
                SetScore(6, 4)
            )
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    @Test
    fun `classic - tiebreak 7-6 without tiebreak score is invalid`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(7, 6), SetScore(6, 4))
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - 7-5 valid extended set`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(7, 5), SetScore(6, 2))
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    @Test
    fun `classic - bagel 6-0`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 0), SetScore(6, 0))
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    @Test
    fun `classic - invalid score 6-5`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 5), SetScore(6, 3))
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - invalid score 8-6`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(8, 6), SetScore(6, 3))
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - empty sets returns invalid`() {
        val result = PadelScoreValidator.validateMatchScore(emptyList())
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - too many sets returns invalid`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 3), SetScore(3, 6), SetScore(6, 4), SetScore(6, 2))
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - match already decided but extra set`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 3), SetScore(6, 4), SetScore(6, 2)),
            totalSets = 3
        )
        // After 2 sets team1 already won 2-0 — third set should not exist
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `classic - incomplete match`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(6, 3))
        )
        // Only 1 set won — need 2 to win best-of-3
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    // ============ Classic with Custom Games Per Set ============

    @Test
    fun `classic - custom 4 games per set`() {
        val result = PadelScoreValidator.validateMatchScore(
            listOf(SetScore(4, 2), SetScore(4, 1)),
            gamesPerSet = 4
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    // ============ Express Format (points-based) ============

    @Test
    fun `express - single set 8 points team1 wins`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(8, 5)),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    @Test
    fun `express - single set 8 points team2 wins`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(3, 8)),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(2, result.winner)
    }

    @Test
    fun `express - 16 points format`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(16, 12)),
            maxPoints = 16,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
    }

    @Test
    fun `express - both teams at max is invalid`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(8, 8)),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `express - exceeding max points is invalid`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(9, 5)),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `express - neither team reaches max is invalid`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(7, 5)),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    @Test
    fun `express - best of 3 sets`() {
        val result = PadelScoreValidator.validateExpressScore(
            listOf(SetScore(8, 3), SetScore(5, 8), SetScore(8, 6)),
            maxPoints = 8,
            totalSets = 3
        )
        assertIs<ScoreValidationResult.Valid>(result)
        assertEquals(1, result.winner)
        assertEquals(2 to 1, result.setsWon)
    }

    @Test
    fun `express - empty sets returns invalid`() {
        val result = PadelScoreValidator.validateExpressScore(
            emptyList(),
            maxPoints = 8,
            totalSets = 1
        )
        assertIs<ScoreValidationResult.Invalid>(result)
    }

    // ============ isValidSetScore ============

    @Test
    fun `isValidSetScore - standard scores`() {
        // Valid scores
        assert(PadelScoreValidator.isValidSetScore(6, 0))
        assert(PadelScoreValidator.isValidSetScore(6, 1))
        assert(PadelScoreValidator.isValidSetScore(6, 2))
        assert(PadelScoreValidator.isValidSetScore(6, 3))
        assert(PadelScoreValidator.isValidSetScore(6, 4))
        assert(PadelScoreValidator.isValidSetScore(7, 5))
        assert(PadelScoreValidator.isValidSetScore(7, 6))
        // Reversed (team2 wins)
        assert(PadelScoreValidator.isValidSetScore(0, 6))
        assert(PadelScoreValidator.isValidSetScore(4, 6))
        assert(PadelScoreValidator.isValidSetScore(5, 7))
        assert(PadelScoreValidator.isValidSetScore(6, 7))
    }

    @Test
    fun `isValidSetScore - invalid scores`() {
        assert(!PadelScoreValidator.isValidSetScore(6, 5))
        assert(!PadelScoreValidator.isValidSetScore(6, 6))
        assert(!PadelScoreValidator.isValidSetScore(8, 6))
        assert(!PadelScoreValidator.isValidSetScore(5, 5))
        assert(!PadelScoreValidator.isValidSetScore(3, 3))
    }

    @Test
    fun `isValidSetScore - no tiebreak allowed`() {
        // 7-6 should be invalid when tiebreak not allowed
        assert(!PadelScoreValidator.isValidSetScore(7, 6, allowTiebreak = false))
        assert(!PadelScoreValidator.isValidSetScore(7, 5, allowTiebreak = false))
        // But regular wins still valid
        assert(PadelScoreValidator.isValidSetScore(6, 4, allowTiebreak = false))
    }
}
