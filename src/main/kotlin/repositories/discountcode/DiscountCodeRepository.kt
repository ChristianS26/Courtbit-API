package repositories.discountcode

import models.discountcode.DiscountCode
import models.discountcode.DiscountCodeUsageResponse
import models.discountcode.ValidateDiscountCodeResponse

interface DiscountCodeRepository {
    suspend fun create(code: DiscountCode): DiscountCode?
    suspend fun getByOrganizerId(organizerId: String): List<DiscountCode>
    suspend fun getById(id: String): DiscountCode?
    suspend fun update(id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>): Boolean
    suspend fun delete(id: String): Boolean
    suspend fun validateCode(code: String, tournamentId: String, playerUid: String): ValidateDiscountCodeResponse
    suspend fun applyCode(
        code: String, tournamentId: String, playerUid: String, partnerUid: String,
        categoryId: String, playerName: String, restriction: String?,
        usedByEmail: String?, originalAmount: Int?
    ): ValidateDiscountCodeResponse
    suspend fun getUsagesByOrganizerId(organizerId: String): List<DiscountCodeUsageResponse>
}
