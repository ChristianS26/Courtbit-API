package services.padelclub

import models.padelclub.PadelClubResponseDto
import repositories.padelclub.PadelClubRepository

class PadelClubService(
    private val repository: PadelClubRepository
) {
    suspend fun getAllPadelClubs(): List<PadelClubResponseDto> = repository.getAll()

    suspend fun getPadelClubById(id: Int): PadelClubResponseDto? = repository.getById(id)

    suspend fun getPadelClubsByCityId(cityId: Int): List<PadelClubResponseDto> = repository.getByCityId(cityId)
}
