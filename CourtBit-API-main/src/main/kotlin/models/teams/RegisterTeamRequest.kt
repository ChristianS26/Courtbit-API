package models.teams

@kotlinx.serialization.Serializable
data class RegisterTeamRequest(
    val playerUid: String,
    val partnerUid: String,
    val tournamentId: String,
    val categoryId: Int
)
