package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Embedded user profile for LeaguePlayer responses.
 * Only populated when the player has a userUid (is a CourtBit user).
 * This data is fetched via JOIN from the users table, so it's always in sync.
 */
@Serializable
data class LeaguePlayerUser(
    val uid: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("photo_url") val photoUrl: String? = null
)

@Serializable
data class LeaguePlayerResponse(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_uid") val userUid: String?,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("is_waiting_list") val isWaitingList: Boolean = false,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("discount_reason") val discountReason: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    @SerialName("shirt_name") val shirtName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("has_paid") val hasPaid: Boolean = false,
    // Embedded CourtBit user profile (null for manually-entered players)
    val user: LeaguePlayerUser? = null
)

@Serializable
data class CreateLeaguePlayerRequest(
    @SerialName("category_id") val categoryId: String,
    @SerialName("user_uid") val userUid: String? = null,
    val name: String,
    val email: String?,
    @SerialName("phone_number") val phoneNumber: String?,
    @SerialName("discount_amount") val discountAmount: Long = 0,
    @SerialName("discount_reason") val discountReason: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    @SerialName("shirt_name") val shirtName: String? = null
)

@Serializable
data class UpdateLeaguePlayerRequest(
    val name: String? = null,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("discount_amount") val discountAmount: Long? = null,
    @SerialName("discount_reason") val discountReason: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    @SerialName("shirt_name") val shirtName: String? = null
)

/**
 * Request for player self-registration (player registers themselves)
 * Unlike CreateLeaguePlayerRequest, this doesn't have discount fields
 * and the userUid is extracted from the JWT token
 */
@Serializable
data class SelfRegisterRequest(
    @SerialName("category_id") val categoryId: String,
    val name: String,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    @SerialName("shirt_name") val shirtName: String? = null
)

/**
 * Error response for self-registration failures
 */
@Serializable
data class SelfRegisterError(
    val error: String,
    val code: String
)

/**
 * Response for user's league registrations with season and category info
 */
@Serializable
data class MyLeagueRegistrationResponse(
    @SerialName("player_id") val playerId: String,
    @SerialName("is_waiting_list") val isWaitingList: Boolean,
    @SerialName("has_paid") val hasPaid: Boolean,
    @SerialName("registered_at") val registeredAt: String,
    val season: SeasonInfo,
    val category: CategoryInfo
) {
    @Serializable
    data class SeasonInfo(
        val id: String,
        val name: String,
        @SerialName("start_date") val startDate: String,
        @SerialName("end_date") val endDate: String?,
        @SerialName("is_active") val isActive: Boolean,
        @SerialName("organizer_name") val organizerName: String?
    )

    @Serializable
    data class CategoryInfo(
        val id: String,
        val name: String,
        val level: String,
        @SerialName("color_hex") val colorHex: String
    )
}

// MARK: - Replace Player

/**
 * Request to replace an existing player with a new player
 * The new player inherits all schedule assignments from the old player
 */
@Serializable
data class ReplacePlayerRequest(
    @SerialName("user_uid") val userUid: String? = null,
    val name: String,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("shirt_size") val shirtSize: String? = null,
    @SerialName("shirt_name") val shirtName: String? = null
)

/**
 * Response after replacing a player
 * Includes the new player and counts of affected records
 */
@Serializable
data class ReplacePlayerResponse(
    @SerialName("new_player") val newPlayer: LeaguePlayerResponse,
    @SerialName("affected_day_groups") val affectedDayGroups: Int,
    @SerialName("affected_matches") val affectedMatches: Int
)

// MARK: - Can Delete Player

/**
 * Response for checking if a player can be safely deleted
 */
@Serializable
data class CanDeletePlayerResponse(
    @SerialName("can_delete") val canDelete: Boolean,
    @SerialName("has_matches") val hasMatches: Boolean,
    @SerialName("match_count") val matchCount: Int
)
