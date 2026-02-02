package models.teams

import com.incodap.models.users.UserDto

fun UserDto.toTeamPlayerDto(): TeamPlayerDto = TeamPlayerDto(
    uid = uid,
    firstName = firstName,
    lastName = lastName,
    photoUrl = photoUrl,
    phone = phone,
    gender = gender,
    email = email,
    isManual = false
)

/**
 * Creates a TeamPlayerDto for a manual player (no CourtBit account)
 */
fun createManualPlayerDto(
    name: String,
    email: String? = null,
    phone: String? = null
): TeamPlayerDto {
    val nameParts = name.trim().split(" ", limit = 2)
    return TeamPlayerDto(
        uid = null,
        firstName = nameParts.getOrElse(0) { name },
        lastName = nameParts.getOrElse(1) { "" },
        photoUrl = null,
        phone = phone,
        gender = null,
        email = email,
        isManual = true
    )
}
