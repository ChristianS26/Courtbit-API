package com.incodap.repositories.users

import com.incodap.models.users.UserDto
import models.profile.UpdateProfileRequest
import models.users.DeleteUserResult

interface UserRepository {
    suspend fun findByEmail(email: String): UserDto?
    suspend fun findByUid(uid: String): UserDto?
    suspend fun insertUser(userDto: UserDto): Boolean
    suspend fun updateProfile(uid: String, request: UpdateProfileRequest): UserDto?
    suspend fun updateProfilePhoto(uid: String, photoUrl: String): Boolean
    suspend fun updatePassword(uid: String, newPasswordHash: String): Boolean
    suspend fun searchUsers(query: String): List<UserDto>
    suspend fun getStripeCustomerIdByUid(uid: String): String?
    suspend fun updateStripeCustomerId(uid: String, customerId: String): Boolean
    suspend fun deleteByUid(uid: String): DeleteUserResult
}
