package com.incodap.models.users

import models.users.PublicUser

fun UserDto.toPublicUser(): PublicUser {
    return PublicUser(
        uid = uid,
        email = email,
        firstName = firstName,
        lastName = lastName,
        phone = phone,
        countryIso = countryIso,
        gender = gender,
        role = role,
        photoUrl = photoUrl,
        emailVerified = emailVerified,
        shirtSize = shirtSize,
    )
}
