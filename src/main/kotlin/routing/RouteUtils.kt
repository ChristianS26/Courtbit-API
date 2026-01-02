package routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*

/**
 * Receives and deserializes the request body to the specified type.
 * Provides a clear error message when Content-Type header is missing.
 *
 * @param T The type to deserialize the request body into
 * @return The deserialized request object
 * @throws ContentTypeException when Content-Type header is missing or incorrect
 * @throws Exception for other deserialization errors
 */
suspend inline fun <reified T : Any> ApplicationCall.receiveWithContentTypeCheck(): T {
    try {
        return this.receive<T>()
    } catch (e: Exception) {
        // Check if the error is due to missing or incorrect Content-Type header
        val contentType = this.request.contentType()
        if (contentType.toString().isEmpty() || !contentType.match(ContentType.Application.Json)) {
            throw ContentTypeException(
                "Missing 'Content-Type: application/json' header. Please add this header to your request."
            )
        }
        // Re-throw the original exception if it's not a Content-Type issue
        throw e
    }
}

/**
 * Exception thrown when Content-Type header is missing or incorrect
 */
class ContentTypeException(message: String) : Exception(message)
