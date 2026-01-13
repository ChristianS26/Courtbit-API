package models.league

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MatchDayResponse(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class DayGroupResponse(
    val id: String,
    @SerialName("match_day_id") val matchDayId: String,
    @SerialName("group_number") val groupNumber: Int,
    @SerialName("player_ids") val playerIds: List<String>,
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?,
    @SerialName("created_at") val createdAt: String,
    val players: List<LeaguePlayerResponse>? = null
)

@Serializable
data class UpdateDayGroupScheduleRequest(
    @SerialName("match_date") val matchDate: String?,
    @SerialName("time_slot") val timeSlot: String?,
    @SerialName("court_index") val courtIndex: Int?
)

@Serializable
data class RotationResponse(
    val id: String,
    @SerialName("day_group_id") val dayGroupId: String,
    @SerialName("rotation_number") val rotationNumber: Int,
    @SerialName("created_at") val createdAt: String,
    val match: DoublesMatchResponse? = null
)

@Serializable
data class DoublesMatchResponse(
    val id: String,
    @SerialName("rotation_id") val rotationId: String,
    @SerialName("team1_player1_id") val team1Player1Id: String?,
    @SerialName("team1_player2_id") val team1Player2Id: String?,
    @SerialName("team2_player1_id") val team2Player1Id: String?,
    @SerialName("team2_player2_id") val team2Player2Id: String?,
    @SerialName("score_team1") val scoreTeam1: Int?,
    @SerialName("score_team2") val scoreTeam2: Int?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("team1_player1") val team1Player1: LeaguePlayerResponse? = null,
    @SerialName("team1_player2") val team1Player2: LeaguePlayerResponse? = null,
    @SerialName("team2_player1") val team2Player1: LeaguePlayerResponse? = null,
    @SerialName("team2_player2") val team2Player2: LeaguePlayerResponse? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    // Forfeit fields
    @SerialName("is_forfeit") val isForfeit: Boolean = false,
    @SerialName("forfeited_player_ids") val forfeitedPlayerIds: List<String> = emptyList(),
    @SerialName("forfeit_recorded_by_uid") val forfeitRecordedByUid: String? = null,
    @SerialName("forfeit_recorded_at") val forfeitRecordedAt: String? = null
)

@Serializable
data class UpdateMatchScoreRequest(
    @SerialName("score_team1") val scoreTeam1: Int,
    @SerialName("score_team2") val scoreTeam2: Int
)

@Serializable
data class UserScoreRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("score_team1") val scoreTeam1: Int,
    @SerialName("score_team2") val scoreTeam2: Int
)

@Serializable
data class MarkForfeitRequest(
    @SerialName("forfeited_player_ids") val forfeitedPlayerIds: List<String>
)

@Serializable
data class ReverseForfeitRequest(
    @SerialName("clear_scores") val clearScores: Boolean = true
)
