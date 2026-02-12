package services.registrationcode

import models.registrationcode.RegistrationCode
import models.registrationcode.RegistrationCodeWithTournamentInfo
import repositories.registrationcode.RegistrationCodeRepository

class RegistrationCodeService(
    private val repository: RegistrationCodeRepository
) {
    suspend fun createRegistrationCode(email: String, organizerId: String? = null): String {
        return repository.createCode(email, organizerId)
    }

    suspend fun getAllRegistrationCodes(): List<RegistrationCode> {
        return repository.getAllCodes()
    }

    suspend fun getRegistrationCodesByOrganizer(organizerId: String): List<RegistrationCode> {
        return repository.getCodesByOrganizerId(organizerId)
    }

    suspend fun getAllRegistrationCodesWithTournamentInfo(): List<RegistrationCodeWithTournamentInfo> {
        return repository.getAllCodesWithTournamentInfo()
    }

    suspend fun getRegistrationCodesByOrganizerWithTournamentInfo(organizerId: String): List<RegistrationCodeWithTournamentInfo> {
        return repository.getCodesByOrganizerWithTournamentInfo(organizerId)
    }
}
