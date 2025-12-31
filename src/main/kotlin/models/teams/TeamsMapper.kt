package models.teams

import com.incodap.models.users.UserDto

fun UserDto.toTeamPlayerDto(): TeamPlayerDto = TeamPlayerDto(
    uid = uid,
    firstName = firstName,
    lastName = lastName,
    photoUrl = photoUrl,
    phone = phone,
    gender = gender
)
