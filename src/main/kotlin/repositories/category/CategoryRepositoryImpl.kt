package repositories.category

import com.incodap.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import models.category.CategoryPosition
import models.category.CategoryPriceResponse
import models.category.CategoryResponseDto
import models.category.TournamentCategoryDto
import models.category.TournamentCategoryPair
import models.category.TournamentCategoryRequest

class CategoryRepositoryImpl(
    private val client: HttpClient,
    private val config: SupabaseConfig,
    private val json: Json
) : CategoryRepository {

    override suspend fun getAll(): List<CategoryResponseDto> {
        val response = client.get("${config.apiUrl}/categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("select", "*")
            parameter("order", "position.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(CategoryResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }

    override suspend fun assignCategoriesToTournament(request: TournamentCategoryRequest): Boolean {
        // Support both formats: simple categoryIds list or categories with colors
        val payload = if (request.categories != null) {
            // New format with colors
            request.categories.map { cat ->
                TournamentCategoryPair(
                    tournament_id = request.tournamentId,
                    category_id = cat.categoryId,
                    color = cat.color
                )
            }
        } else {
            // Legacy format: just category IDs
            request.categoryIds?.map { categoryId ->
                TournamentCategoryPair(
                    tournament_id = request.tournamentId,
                    category_id = categoryId,
                    color = null
                )
            } ?: emptyList()
        }


        val response = client.post("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    ListSerializer(TournamentCategoryPair.serializer()),
                    payload
                )
            )
        }

        val body = response.bodyAsText()

        return response.status.isSuccess()
    }

    override suspend fun getCategoriesByTournamentId(tournamentId: String): List<TournamentCategoryDto> {
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "category_id,color,categories(name,position)")
            parameter("order", "categories(position)")
        }

        return if (response.status.isSuccess()) {
            val raw = response.bodyAsText()
            json.decodeFromString(
                ListSerializer(JsonObject.serializer()),
                raw
            ).map {
                TournamentCategoryDto(
                    id = it["category_id"]?.jsonPrimitive?.content ?: "",
                    name = it["categories"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                    position = it["categories"]?.jsonObject?.get("position")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
                    color = it["color"]?.jsonPrimitive?.content
                )
            }.sortedBy { it.position }
        } else {
            emptyList()
        }
    }

    override suspend fun getCategoryPricesForTournament(
        tournamentId: String,
        tournamentType: String
    ): List<CategoryPriceResponse> {

        // Obtener IDs de categoría asociadas al torneo
        val categoryIdsResponse = client.get("${config.apiUrl}/tournament_categories?select=category_id&tournament_id=eq.$tournamentId") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }

        val body = categoryIdsResponse.bodyAsText()
        // Cambia a Map<String, Int>
        val categoryIdMaps = json.decodeFromString<List<Map<String, Int>>>(body)
        val categoryIds = categoryIdMaps.mapNotNull { it["category_id"] }


        if (categoryIds.isEmpty()) return emptyList()

        // Armar el IN sin comillas
        val idsInClause = categoryIds.joinToString(",") { it.toString() }

        // Obtener precios por categoría
        val pricesResponse = client.get("${config.apiUrl}/category_prices?category_id=in.($idsInClause)&tournament_type=ilike.$tournamentType") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }
        val pricesJson = pricesResponse.bodyAsText()
        val prices = json.decodeFromString<List<CategoryPriceResponse>>(pricesJson)

        // Obtener posiciones
        val positionsResponse = client.get("${config.apiUrl}/categories?select=id,position&id=in.($idsInClause)") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
        }
        val positionsJson = positionsResponse.bodyAsText()
        val positions = json.decodeFromString<List<CategoryPosition>>(positionsJson)

        // Mapear y ordenar
        val positionMap = positions.associateBy { it.id }

        val sorted = prices.sortedBy { price ->
            positionMap[price.categoryId.toString()]?.position ?: Int.MAX_VALUE
        }


        return sorted
    }


    override suspend fun getCategoriesByIds(ids: List<Int>): List<CategoryResponseDto> {
        val filter = ids.joinToString(",") { "\"$it\"" } // para in.(...)
        val response = client.get("${config.apiUrl}/categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("id", "in.($filter)")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(response.bodyAsText())
        } else {
            emptyList()
        }
    }

}
