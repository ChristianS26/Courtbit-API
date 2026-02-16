package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeasonResponse(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("registrations_open") val registrationsOpen: Boolean = false,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int = 2,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int = 4,
    @SerialName("organizer_id") val organizerId: String?,
    @SerialName("organizer_name") val organizerName: String?,
    @SerialName("organizer_logo_url") val organizerLogoURL: String? = null,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean = true,
    @SerialName("ranking_criteria") val rankingCriteria: List<String> = listOf("adjusted_points", "point_diff", "games_won"),
    @SerialName("max_points_per_game") val maxPointsPerGame: Int = 6,
    @SerialName("registration_fee") val registrationFee: Long = 0,
    @SerialName("forfeit_winner_points") val forfeitWinnerPoints: Int = 15,
    @SerialName("forfeit_loser_points") val forfeitLoserPoints: Int = 12,
    @SerialName("requires_shirt_size") val requiresShirtSize: Boolean = false,
    @SerialName("requires_shirt_name") val requiresShirtName: Boolean = false,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("featured_zone") val featuredZone: String? = null,
)

@Serializable
data class CreateSeasonRequest(
    val name: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String?,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("registrations_open") val registrationsOpen: Boolean = false,
    @SerialName("matchday_dates") val matchdayDates: List<String>? = null,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int = 2,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int = 4,
    @SerialName("organizer_id") val organizerId: String? = null,
    @SerialName("ranking_criteria") val rankingCriteria: List<String>? = null,
    @SerialName("max_points_per_game") val maxPointsPerGame: Int? = null,
    @SerialName("registration_fee") val registrationFee: Long = 0,
    @SerialName("forfeit_winner_points") val forfeitWinnerPoints: Int = 15,
    @SerialName("forfeit_loser_points") val forfeitLoserPoints: Int = 12,
    @SerialName("requires_shirt_size") val requiresShirtSize: Boolean = false,
    @SerialName("requires_shirt_name") val requiresShirtName: Boolean = false,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("number_of_courts") val numberOfCourts: Int = 4  // Used to create court records, not persisted to seasons table
)

@Serializable
data class UpdateSeasonRequest(
    val name: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("registrations_open") val registrationsOpen: Boolean? = null,
    @SerialName("allow_player_scores") val allowPlayerScores: Boolean? = null,
    @SerialName("ranking_criteria") val rankingCriteria: List<String>? = null,
    @SerialName("max_points_per_game") val maxPointsPerGame: Int? = null,
    @SerialName("registration_fee") val registrationFee: Long? = null,
    @SerialName("forfeit_winner_points") val forfeitWinnerPoints: Int? = null,
    @SerialName("forfeit_loser_points") val forfeitLoserPoints: Int? = null,
    @SerialName("requires_shirt_size") val requiresShirtSize: Boolean? = null,
    @SerialName("requires_shirt_name") val requiresShirtName: Boolean? = null,
    @SerialName("players_direct_to_final") val playersDirectToFinal: Int? = null,
    @SerialName("players_in_semifinals") val playersInSemifinals: Int? = null,
    val location: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
