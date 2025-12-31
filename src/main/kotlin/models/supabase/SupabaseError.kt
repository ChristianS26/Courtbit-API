package models.supabase

import kotlinx.serialization.Serializable

@Serializable
data class SupabaseError(
    val message: String? = null,
    val details: String? = null,
    val hint: String? = null,
    val code: String? = null
) {
    override fun toString(): String =
        "message=$message details=$details hint=$hint code=$code"
}
