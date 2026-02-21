package com.incodap.models.teams

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RpcRegistrationRowDto(
    val team_id: String,
    val tournament_id: String,
    val tournament_name: String? = null,
    val tournament_start: String? = null,   // ISO-8601 como String
    val tournament_end: String? = null,
    val tournament_status: String? = null,  // "active" | "upcoming" | "past"
    val tournament_type: String? = null,    // "singles" | "doubles"
    val organizer_name: String? = null,     // Organizer display name

    val category_id: Int,
    val category_name: String,

    val i_am_player_a: Boolean,
    val paid_by_me: Boolean,
    val paid_by_partner: Boolean,

    // Partner puede no existir; hazlo nullable para mapear con let
    val partner_uid: String? = null,
    val partner_first_name: String? = null,
    val partner_last_name: String? = null,
    val partner_photo_url: String? = null,
    // Si el RPC aún no los expone, déjalos en null por defecto
    val partner_phone: String? = null,
    val partner_gender: String? = null,
    val schedule_restriction: JsonElement? = null
)
