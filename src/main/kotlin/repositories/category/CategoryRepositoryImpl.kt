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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
            parameter("order", "category_type.asc,level.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(CategoryResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }

    override suspend fun assignCategoriesToTournament(request: TournamentCategoryRequest): Boolean {
        // Support both formats: simple categoryIds list or categories with colors/maxTeams
        val payload = if (request.categories != null) {
            // New format with colors and maxTeams
            request.categories.map { cat ->
                TournamentCategoryPair(
                    tournament_id = request.tournamentId,
                    category_id = cat.categoryId,
                    color = cat.color,
                    max_teams = cat.maxTeams
                )
            }
        } else {
            // Legacy format: just category IDs
            request.categoryIds?.map { categoryId ->
                TournamentCategoryPair(
                    tournament_id = request.tournamentId,
                    category_id = categoryId,
                    color = null,
                    max_teams = null
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

    override suspend fun getCategoryPrice(tournamentId: String, categoryId: Int): Long? {
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            parameter("select", "price")
        }
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val list = json.decodeFromString<List<JsonObject>>(body)
        return list.firstOrNull()?.get("price")?.jsonPrimitive?.longOrNull
    }

    override suspend fun getCategoriesByTournamentId(tournamentId: String): List<TournamentCategoryDto> {
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "category_id,color,max_teams,price,categories(name,category_type,level)")
            parameter("order", "categories(category_type),categories(level)")
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
                    color = it["color"]?.jsonPrimitive?.contentOrNull,
                    maxTeams = it["max_teams"]?.jsonPrimitive?.intOrNull,
                    price = it["price"]?.jsonPrimitive?.intOrNull
                )
            }
        } else {
            emptyList()
        }
    }

    override suspend fun getCategoryPricesForTournament(
        tournamentId: String,
        tournamentType: String
    ): List<CategoryPriceResponse> {

        // Obtener categorías asociadas al torneo con información de la categoría
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "category_id,price,categories(id,name,category_type,level)")
            parameter("order", "categories(category_type),categories(level)")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val body = response.bodyAsText()
        val rawList = json.decodeFromString<List<JsonObject>>(body)

        return rawList.mapNotNull { obj ->
            val categoryId = obj["category_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val categoryObj = obj["categories"]?.jsonObject ?: return@mapNotNull null
            val name = categoryObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val price = obj["price"]?.jsonPrimitive?.intOrNull ?: 0

            CategoryPriceResponse(
                id = null,
                tournamentType = tournamentType,
                categoryId = categoryId,
                price = price,
                categoryName = name
            )
        }.sortedBy { it.categoryId }
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

    override suspend fun getNaturalCategories(gender: String?): List<CategoryResponseDto> {
        val response = client.get("${config.apiUrl}/categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("category_type", "eq.natural")
            if (gender != null) {
                parameter("gender", "eq.$gender")
            }
            parameter("order", "gender.asc,level.asc")
        }

        return if (response.status.isSuccess()) {
            json.decodeFromString(
                ListSerializer(CategoryResponseDto.serializer()),
                response.bodyAsText()
            )
        } else emptyList()
    }

    override suspend fun updateCategoryMaxTeams(tournamentId: String, categoryId: Int, maxTeams: Int?): Boolean {
        val body = if (maxTeams != null) {
            json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), mapOf("max_teams" to maxTeams))
        } else {
            """{"max_teams": null}"""
        }

        val response = client.patch("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            parameter("tournament_id", "eq.$tournamentId")
            parameter("category_id", "eq.$categoryId")
            setBody(body)
        }

        return response.status.isSuccess()
    }

}
