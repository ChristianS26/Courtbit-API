package com.incodap.repositories.club

import com.incodap.models.club.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class ClubRepositoryImpl(
    private val client: HttpClient,
    private val supabaseUrl: String,
    private val supabaseKey: String
) : ClubRepository {

    private val clubsTable = "$supabaseUrl/rest/v1/clubs"
    private val courtsTable = "$supabaseUrl/rest/v1/club_courts"

    override suspend fun getClubs(organizerId: String): List<Club> {
        return client.get(clubsTable) {
            parameter("organizer_id", "eq.$organizerId")
            parameter("order", "name.asc")
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
        }.body()
    }

    override suspend fun getClub(clubId: String): Club? {
        val clubs: List<Club> = client.get(clubsTable) {
            parameter("id", "eq.$clubId")
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
        }.body()
        return clubs.firstOrNull()
    }

    override suspend fun getClubWithCourts(clubId: String): ClubWithCourtsResponse? {
        val club = getClub(clubId) ?: return null
        val courts = getClubCourts(clubId)
        return ClubWithCourtsResponse(
            id = club.id,
            organizerId = club.organizerId,
            name = club.name,
            address = club.address,
            latitude = club.latitude,
            longitude = club.longitude,
            logoUrl = club.logoUrl,
            createdAt = club.createdAt,
            updatedAt = club.updatedAt,
            courts = courts
        )
    }

    override suspend fun createClub(organizerId: String, request: CreateClubRequest): Club {
        val body = buildJsonObject {
            put("organizer_id", organizerId)
            put("name", request.name)
            request.address?.let { put("address", it) }
            request.latitude?.let { put("latitude", it) }
            request.longitude?.let { put("longitude", it) }
            request.logoUrl?.let { put("logo_url", it) }
        }

        val clubs: List<Club> = client.post(clubsTable) {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
            header("Prefer", "return=representation")
            setBody(body)
        }.body()
        return clubs.first()
    }

    override suspend fun updateClub(clubId: String, request: UpdateClubRequest): Club? {
        val body = buildJsonObject {
            request.name?.let { put("name", it) }
            request.address?.let { put("address", it) }
            request.latitude?.let { put("latitude", it) }
            request.longitude?.let { put("longitude", it) }
            request.logoUrl?.let { put("logo_url", it) }
        }

        val clubs: List<Club> = client.patch(clubsTable) {
            parameter("id", "eq.$clubId")
            contentType(ContentType.Application.Json)
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
            header("Prefer", "return=representation")
            setBody(body)
        }.body()
        return clubs.firstOrNull()
    }

    override suspend fun deleteClub(clubId: String): Boolean {
        val response = client.delete(clubsTable) {
            parameter("id", "eq.$clubId")
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
        }
        return response.status.isSuccess()
    }

    override suspend fun getClubCourts(clubId: String): List<ClubCourt> {
        return client.get(courtsTable) {
            parameter("club_id", "eq.$clubId")
            parameter("order", "court_number.asc")
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
        }.body()
    }

    override suspend fun createClubCourt(clubId: String, request: CreateClubCourtRequest): ClubCourt {
        val body = buildJsonObject {
            put("club_id", clubId)
            put("court_number", request.courtNumber)
            put("name", request.name)
            request.availableFrom?.let { put("available_from", it) }
            request.availableTo?.let { put("available_to", it) }
        }

        val courts: List<ClubCourt> = client.post(courtsTable) {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
            header("Prefer", "return=representation")
            setBody(body)
        }.body()
        return courts.first()
    }

    override suspend fun updateClubCourt(courtId: String, request: UpdateClubCourtRequest): ClubCourt? {
        val body = buildJsonObject {
            request.courtNumber?.let { put("court_number", it) }
            request.name?.let { put("name", it) }
            request.availableFrom?.let { put("available_from", it) }
            request.availableTo?.let { put("available_to", it) }
            request.isActive?.let { put("is_active", it) }
        }

        val courts: List<ClubCourt> = client.patch(courtsTable) {
            parameter("id", "eq.$courtId")
            contentType(ContentType.Application.Json)
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
            header("Prefer", "return=representation")
            setBody(body)
        }.body()
        return courts.firstOrNull()
    }

    override suspend fun deleteClubCourt(courtId: String): Boolean {
        val response = client.delete(courtsTable) {
            parameter("id", "eq.$courtId")
            header("apikey", supabaseKey)
            header("Authorization", "Bearer $supabaseKey")
        }
        return response.status.isSuccess()
    }
}
