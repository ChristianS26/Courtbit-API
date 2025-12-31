package services.registrationcode

import models.registrationcode.RegistrationCode
import models.registrationcode.RegistrationCodeWithTournamentInfo
import repositories.registrationcode.RegistrationCodeRepository

class RegistrationCodeService(
    private val repository: RegistrationCodeRepository
) {
    suspend fun createRegistrationCode(email: String): String {
        return repository.createCode(email)
    }

    suspend fun getAllRegistrationCodes(): List<RegistrationCode> {
        return repository.getAllCodes()
    }

    suspend fun getAllRegistrationCodesWithTournamentInfo(): List<RegistrationCodeWithTournamentInfo> {
        return repository.getAllCodesWithTournamentInfo()
    }
}
