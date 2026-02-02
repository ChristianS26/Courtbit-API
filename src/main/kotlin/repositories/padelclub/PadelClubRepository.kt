package repositories.padelclub

import models.padelclub.PadelClubResponseDto

interface PadelClubRepository {
    suspend fun getAll(): List<PadelClubResponseDto>
    suspend fun getById(id: Int): PadelClubResponseDto?
    suspend fun getByCityId(cityId: Int): List<PadelClubResponseDto>
}
