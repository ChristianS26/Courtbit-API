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

    override suspend fun getCategoriesByTournamentId(tournamentId: String): List<TournamentCategoryDto> {
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "category_id,color,max_teams,categories(name,position)")
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
                    color = it["color"]?.jsonPrimitive?.content,
                    maxTeams = it["max_teams"]?.jsonPrimitive?.intOrNull
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

        // Obtener categorías asociadas al torneo con información de la categoría
        val response = client.get("${config.apiUrl}/tournament_categories") {
            header("apikey", config.apiKey)
            header("Authorization", "Bearer ${config.apiKey}")
            parameter("tournament_id", "eq.$tournamentId")
            parameter("select", "category_id,categories(id,name,position)")
            parameter("order", "categories(position)")
        }

        if (!response.status.isSuccess()) {
            return emptyList()
        }

        val body = response.bodyAsText()
        val rawList = json.decodeFromString<List<JsonObject>>(body)

        // Mapear a CategoryPriceResponse con precio 0 (inscripciones gratuitas)
        return rawList.mapNotNull { obj ->
            val categoryId = obj["category_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val categoryObj = obj["categories"]?.jsonObject ?: return@mapNotNull null
            val name = categoryObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val position = categoryObj["position"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE

            CategoryPriceResponse(
                id = null,
                tournamentType = tournamentType,
                categoryId = categoryId,
                price = 0, // Inscripciones gratuitas
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
