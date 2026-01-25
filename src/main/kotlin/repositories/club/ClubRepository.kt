package com.incodap.repositories.club

import com.incodap.models.club.*

interface ClubRepository {
    suspend fun getClubs(organizerId: String): List<Club>
    suspend fun getClub(clubId: String): Club?
    suspend fun getClubWithCourts(clubId: String): ClubWithCourtsResponse?
    suspend fun createClub(organizerId: String, request: CreateClubRequest): Club
    suspend fun updateClub(clubId: String, request: UpdateClubRequest): Club?
    suspend fun deleteClub(clubId: String): Boolean

    suspend fun getClubCourts(clubId: String): List<ClubCourt>
    suspend fun createClubCourt(clubId: String, request: CreateClubCourtRequest): ClubCourt
    suspend fun updateClubCourt(courtId: String, request: UpdateClubCourtRequest): ClubCourt?
    suspend fun deleteClubCourt(courtId: String): Boolean
}
