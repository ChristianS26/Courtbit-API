package com.incodap.services.club

import com.incodap.models.club.*
import com.incodap.repositories.club.ClubRepository

class ClubService(private val repository: ClubRepository) {

    suspend fun getClubs(organizerId: String): List<Club> {
        return repository.getClubs(organizerId)
    }

    suspend fun getClub(clubId: String): Club? {
        return repository.getClub(clubId)
    }

    suspend fun getClubWithCourts(clubId: String): ClubWithCourtsResponse? {
        return repository.getClubWithCourts(clubId)
    }

    suspend fun createClub(organizerId: String, request: CreateClubRequest): Club {
        require(request.name.isNotBlank()) { "Club name is required" }
        return repository.createClub(organizerId, request)
    }

    suspend fun updateClub(clubId: String, request: UpdateClubRequest): Club? {
        request.name?.let { require(it.isNotBlank()) { "Club name cannot be blank" } }
        return repository.updateClub(clubId, request)
    }

    suspend fun deleteClub(clubId: String): Boolean {
        return repository.deleteClub(clubId)
    }

    suspend fun getClubCourts(clubId: String): List<ClubCourt> {
        return repository.getClubCourts(clubId)
    }

    suspend fun createClubCourt(clubId: String, request: CreateClubCourtRequest): ClubCourt {
        require(request.courtNumber > 0) { "Court number must be positive" }
        require(request.name.isNotBlank()) { "Court name is required" }
        return repository.createClubCourt(clubId, request)
    }

    suspend fun updateClubCourt(courtId: String, request: UpdateClubCourtRequest): ClubCourt? {
        request.courtNumber?.let { require(it > 0) { "Court number must be positive" } }
        request.name?.let { require(it.isNotBlank()) { "Court name cannot be blank" } }
        return repository.updateClubCourt(courtId, request)
    }

    suspend fun deleteClubCourt(courtId: String): Boolean {
        return repository.deleteClubCourt(courtId)
    }
}
