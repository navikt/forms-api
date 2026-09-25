package no.nav.forms

import no.nav.forms.model.ErrorResponseDto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals

data class FormsApiResponse<T>(
    val statusCode: HttpStatusCode,
    private val response: Pair<T?, ErrorResponseDto?>,
) {
    val body: T
        get() {
            assertTrue(statusCode.is2xxSuccessful, "Expected success")
            return response.first!!
        }

    val errorBody: ErrorResponseDto
        get() {
            assertFalse(statusCode.is2xxSuccessful, "Expected failure")
            return response.second!!
        }

    fun assertSuccess(): FormsApiResponse<T> {
        assertTrue(statusCode.is2xxSuccessful, "Expected successful response code")
        return this
    }

    fun assertClientError(): FormsApiResponse<T> {
        assertTrue(statusCode.is4xxClientError, "Expected client error")
        return this
    }

    fun assertHttpStatus(status: HttpStatus): FormsApiResponse<T> {
        assertEquals(status.value(), statusCode.value())
        return this
    }
}

fun apiHeaders(token: String?, additionalHeaders: Map<String, String>? = emptyMap()): HttpHeaders =
    HttpHeaders().apply {
        token?.let { setBearerAuth(it) }
        additionalHeaders?.forEach { add(it.key, it.value) }
    }

fun <T> parseSingleResponse(
    response: ResponseEntity<String>,
    clazz: Class<T>,
    mapper: ObjectMapper
): Pair<T?, ErrorResponseDto?> =
    when {
        response.statusCode.is2xxSuccessful -> Pair(mapper.readValue(response.body, clazz), null)
        response.body.isNullOrBlank() -> Pair(null, null)
        else -> Pair(null, mapper.readValue(response.body, ErrorResponseDto::class.java))
    }

fun <T> parseListResponse(
    response: ResponseEntity<String>,
    clazz: Class<T>,
    mapper: ObjectMapper
): Pair<List<T>?, ErrorResponseDto?> =
    when {
        response.statusCode.is2xxSuccessful -> Pair(
            mapper.readValue(response.body, mapper.typeFactory.constructCollectionType(List::class.java, clazz)), null
        )
        else -> Pair(null, mapper.readValue(response.body, ErrorResponseDto::class.java))
    }
