package models.teams

@kotlinx.serialization.Serializable
data class RegisterTeamRequest(
    val tournamentId: String,
    val categoryId: Int,
    // Player A - uid OR manual info
    val playerUid: String? = null,
    val playerName: String? = null,
    val playerEmail: String? = null,
    val playerPhone: String? = null,
    // Player B - uid OR manual info
    val partnerUid: String? = null,
    val partnerName: String? = null,
    val partnerEmail: String? = null,
    val partnerPhone: String? = null
)
