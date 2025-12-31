package models.payments

import kotlinx.serialization.Serializable

@Serializable
data class RpcPayAndMarkManualDto(
    val p_team_id: String,
    val p_tournament_id: String,
    val p_paid_by: String,
    val p_method: String,
    val p_admin_uid: String,
    val p_player_uid: String?
)

@Serializable
data class RpcInsertedIdDto(val pay_and_mark_manual: String)
