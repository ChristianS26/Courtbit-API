package services.discountcode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import models.discountcode.*
import repositories.discountcode.DiscountCodeRepository
import java.util.UUID

class DiscountCodeService(
    private val repository: DiscountCodeRepository
) {

    suspend fun createDiscountCode(
        request: CreateDiscountCodeRequest,
        organizerId: String,
        createdByEmail: String
    ): DiscountCode? {
        val code = DiscountCode(
            id = UUID.randomUUID().toString(),
            organizationId = organizerId,
            code = request.code.uppercase().trim(),
            description = request.description,
            discountType = request.discountType,
            discountValue = request.discountValue,
            usageType = request.usageType,
            maxUses = request.maxUses,
            createdByEmail = createdByEmail,
            validFrom = request.validFrom,
            validUntil = request.validUntil,
        )
        return repository.create(code)
    }

    suspend fun getDiscountCodes(organizerId: String): List<DiscountCode> {
        return repository.getByOrganizerId(organizerId)
    }

    suspend fun updateDiscountCode(
        id: String,
        organizerId: String,
        request: UpdateDiscountCodeRequest
    ): Boolean {
        // Verify the code belongs to this org
        val existing = repository.getById(id) ?: return false
        if (existing.organizationId != organizerId) return false

        val fields = mutableMapOf<String, JsonElement>()
        request.isActive?.let { fields["is_active"] = JsonPrimitive(it) }
        request.description?.let { fields["description"] = JsonPrimitive(it) }
        request.validUntil?.let { fields["valid_until"] = JsonPrimitive(it) }
        request.maxUses?.let { fields["max_uses"] = JsonPrimitive(it) }

        if (fields.isEmpty()) return true
        return repository.update(id, fields)
    }

    suspend fun deleteDiscountCode(id: String, organizerId: String): Boolean {
        val existing = repository.getById(id) ?: return false
        if (existing.organizationId != organizerId) return false
        return repository.delete(id)
    }

    suspend fun getUsages(organizerId: String): List<DiscountCodeUsageResponse> {
        return repository.getUsagesByOrganizerId(organizerId)
    }

    suspend fun validateDiscountCode(
        code: String,
        tournamentId: String,
        playerUid: String
    ): ValidateDiscountCodeResponse {
        return repository.validateCode(code, tournamentId, playerUid)
    }

    suspend fun applyDiscountCode(
        code: String, tournamentId: String, playerUid: String, partnerUid: String,
        categoryId: String, playerName: String, restriction: String?,
        usedByEmail: String?, originalAmount: Int?
    ): ValidateDiscountCodeResponse {
        return repository.applyCode(
            code, tournamentId, playerUid, partnerUid,
            categoryId, playerName, restriction, usedByEmail, originalAmount
        )
    }
}
