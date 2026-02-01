package services.city

import models.city.CityResponseDto
import repositories.city.CityRepository

class CityService(
    private val repository: CityRepository
) {
    suspend fun getAllCities(): List<CityResponseDto> = repository.getAll()

    suspend fun getCityById(id: Int): CityResponseDto? = repository.getById(id)
}
