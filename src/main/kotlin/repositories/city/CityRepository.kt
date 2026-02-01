package repositories.city

import models.city.CityResponseDto

interface CityRepository {
    suspend fun getAll(): List<CityResponseDto>
    suspend fun getById(id: Int): CityResponseDto?
}
