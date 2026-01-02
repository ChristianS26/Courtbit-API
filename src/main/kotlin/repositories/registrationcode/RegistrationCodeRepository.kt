package repositories.registrationcode

import models.registrationcode.RegistrationCode
import models.registrationcode.RegistrationCodeWithTournamentInfo

interface RegistrationCodeRepository {
    suspend fun createCode(email: String, organizerId: String? = null): String
    suspend fun getValidCode(code: String): RegistrationCode?
    suspend fun markCodeAsUsed(code: String, usedByEmail: String, tournamentId: String): Boolean
    suspend fun getAllCodes(): List<RegistrationCode>
    suspend fun getAllCodesWithTournamentInfo(): List<RegistrationCodeWithTournamentInfo>
}
