package models.ranking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RankingEventWithTournament(
    @SerialName("tournament") val tournament: JoinedTournament? = null,
    @SerialName("tournament_id") val tournamentId: String? = null,
    @SerialName("tournament_name") val tournamentName: String? = null,
    val position: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("points_earned") val pointsEarned: Int
)

@Serializable
data class JoinedTournament(
    val id: String,
    val name: String,
    @SerialName("start_date") val startDate: String? = null
)
