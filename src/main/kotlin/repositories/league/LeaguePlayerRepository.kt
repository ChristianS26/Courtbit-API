package repositories.league

import models.league.CanDeletePlayerResponse
import models.league.CreateLeaguePlayerRequest
import models.league.LeaguePlayerResponse
import models.league.LinkPlayerResponse
import models.league.MyLeagueRegistrationResponse
import models.league.PendingPlayerLinkResponse
import models.league.ReplacePlayerRequest
import models.league.ReplacePlayerResponse
import models.league.SelfRegisterRequest
import models.league.UpdateLeaguePlayerRequest

interface LeaguePlayerRepository {
    suspend fun getAll(): List<LeaguePlayerResponse>
    suspend fun getByCategoryId(categoryId: String): List<LeaguePlayerResponse>
    suspend fun getById(id: String): LeaguePlayerResponse?
    suspend fun getByUserUidAndCategoryId(userUid: String, categoryId: String): LeaguePlayerResponse?
    suspend fun create(request: CreateLeaguePlayerRequest): LeaguePlayerResponse?
    suspend fun update(id: String, request: UpdateLeaguePlayerRequest): Boolean
    suspend fun delete(id: String): Boolean

    /**
     * Self-registration for players
     * Returns the created player or null if validation fails
     */
    suspend fun selfRegister(userUid: String, request: SelfRegisterRequest): Result<LeaguePlayerResponse>

    /**
     * Get all league registrations for a user with season and category info
     */
    suspend fun getMyRegistrations(userUid: String): List<MyLeagueRegistrationResponse>

    /**
     * Check if a player can be safely deleted
     * A player cannot be deleted if they are assigned to any doubles matches
     */
    suspend fun canDelete(playerId: String): CanDeletePlayerResponse

    /**
     * Replace a player with a new player
     * Updates all day_groups.player_ids and doubles_matches player references
     * Deletes the old player after successful replacement
     */
    suspend fun replacePlayer(oldPlayerId: String, request: ReplacePlayerRequest): Result<ReplacePlayerResponse>

    /**
     * Find manual players (user_uid = null) in active seasons
     * matching the user's email or phone number
     */
    suspend fun findPendingLinks(email: String, phone: String?): List<PendingPlayerLinkResponse>

    /**
     * Link a manual player to a CourtBit user
     * Updates the player's user_uid field
     */
    suspend fun linkPlayerToUser(playerId: String, userUid: String): Result<LinkPlayerResponse>
}
