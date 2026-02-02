package models.teams

@kotlinx.serialization.Serializable
data class ReplacePlayerRequest(
    val teamId: String = "", // Filled from path parameter
    val playerPosition: String, // "a" or "b"
    // New player - either uid OR manual info
    val newPlayerUid: String? = null,
    val newPlayerName: String? = null,
    val newPlayerEmail: String? = null,
    val newPlayerPhone: String? = null
)
