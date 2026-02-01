package com.incodap.models.users
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for user search results with masked personal data.
 * Email and phone are partially hidden to protect user privacy.
 */
@Serializable
data class MaskedUserSearchResult(
    @SerialName("uid")
    val uid: String,

    @SerialName("first_name")
    val firstName: String,

    @SerialName("last_name")
    val lastName: String,

    @SerialName("email")
    val email: String, // Masked: j***@domain.com

    @SerialName("phone")
    val phone: String? = null, // Masked: ***4567

    @SerialName("photo_url")
    val photoUrl: String? = null,
)

/**
 * Masks an email address: "john.doe@example.com" -> "j***@example.com"
 */
fun maskEmail(email: String): String {
    val parts = email.split("@")
    if (parts.size != 2) return "***@***"

    val local = parts[0]
    val domain = parts[1]

    val maskedLocal = if (local.length > 1) "${local[0]}***" else "***"
    return "$maskedLocal@$domain"
}

/**
 * Masks a phone number: "+52 555 123 4567" -> "***4567"
 */
fun maskPhone(phone: String?): String? {
    if (phone.isNullOrBlank()) return null

    val digits = phone.filter { it.isDigit() }
    if (digits.length < 4) return "***"

    return "***${digits.takeLast(4)}"
}

/**
 * Extension function to convert UserDto to MaskedUserSearchResult
 */
fun UserDto.toMaskedSearchResult(): MaskedUserSearchResult {
    return MaskedUserSearchResult(
        uid = uid,
        firstName = firstName,
        lastName = lastName,
        email = maskEmail(email),
        phone = maskPhone(phone),
        photoUrl = photoUrl,
    )
}
