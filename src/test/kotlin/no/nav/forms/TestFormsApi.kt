package no.nav.forms

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.forms.model.*
import no.nav.forms.utils.LanguageCode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.util.MultiValueMap
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

private const val formsapiEntityRevisionHeaderName = "Formsapi-Entity-Revision"

class TestFormsApi(
	private val baseUrl: String,
	private val restTemplate: TestRestTemplate,
	private val objectMapper: ObjectMapper,
) {

	private val globalTranslationBaseUrl = "$baseUrl/v1/global-translations"

	fun createGlobalTranslation(
		request: NewGlobalTranslationRequest,
		authToken: String? = null,
		additionalHeaders: Map<String, String> = emptyMap()
	): FormsApiResponse<GlobalTranslationDto> {
		val response = restTemplate.exchange(
			globalTranslationBaseUrl,
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken, additionalHeaders)),
			String::class.java
		)
		val body = readGlobalTranslationBodyV2(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun putGlobalTranslation(
		id: Long,
		revision: Int,
		request: UpdateGlobalTranslationRequest,
		authToken: String? = null,
		additionalHeaders: Map<String, String> = emptyMap()
	): FormsApiResponse<GlobalTranslationDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to revision.toString())
		val response = restTemplate.exchange(
			"$globalTranslationBaseUrl/$id",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers.plus(additionalHeaders))),
			String::class.java
		)
		val body = readGlobalTranslationBodyV2(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteGlobalTranslation(
		id: Long,
		authToken: String? = null,
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange(
			"$globalTranslationBaseUrl/$id",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken)),
			String::class.java
		)
		val body = if (!response.statusCode.is2xxSuccessful) Pair(null, readErrorBody(response)) else Pair(null, null)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getGlobalTranslations(): FormsApiResponse<List<GlobalTranslationDto>> {
		val responseType = object : ParameterizedTypeReference<List<GlobalTranslationDto>>() {}
		val response = restTemplate.exchange(globalTranslationBaseUrl, HttpMethod.GET, null, responseType)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	private fun readGlobalTranslationBodyV2(response: ResponseEntity<String>): Pair<GlobalTranslationDto?, ErrorResponseDto?> {
		if (response.statusCode.is2xxSuccessful) {
			val body = objectMapper.readValue(
				response.body,
				GlobalTranslationDto::class.java
			)!!
			return Pair(body, null)
		}
		return Pair(null, objectMapper.readValue(response.body, ErrorResponseDto::class.java)!!)
	}

	private fun readErrorBody(response: ResponseEntity<String>): ErrorResponseDto {
		return objectMapper.readValue(response.body, ErrorResponseDto::class.java)
	}

	private fun httpHeaders(
		token: String?,
		additionalHeaders: Map<String, String>? = emptyMap()
	): MultiValueMap<String, String> {
		val headers = HttpHeaders()
		if (token != null) {
			headers.add("Authorization", "Bearer $token")
		}
		additionalHeaders?.forEach { headers.add(it.key, it.value) }
		return headers
	}

	fun createFormTranslation(
		formPath: String,
		request: NewFormTranslationRequestDto,
		authToken: String? = null
	): FormsApiResponse<FormTranslationDto> {
		val response = restTemplate.exchange(
			"$baseUrl/v1/forms/$formPath/translations",
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken)),
			String::class.java
		)
		val body = readFormTranslationBody(response)
		return FormsApiResponse<FormTranslationDto>(response.statusCode, body)
	}

	fun updateFormTranslation(
		formPath: String,
		formTranslationId: Long,
		revision: Int,
		request: UpdateFormTranslationRequest,
		authToken: String? = null,
	): FormsApiResponse<FormTranslationDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to revision.toString())
		val response = restTemplate.exchange(
			"$baseUrl/v1/forms/$formPath/translations/$formTranslationId",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers)),
			String::class.java
		)
		val body = readFormTranslationBody(response)
		return FormsApiResponse<FormTranslationDto>(response.statusCode, body)
	}

	private fun readFormTranslationBody(response: ResponseEntity<String>): Pair<FormTranslationDto?, ErrorResponseDto?> {
		if (response.statusCode.is2xxSuccessful) {
			val body = objectMapper.readValue(
				response.body,
				FormTranslationDto::class.java
			)
			return Pair(body, null)
		}
		val errorBody = objectMapper.readValue(response.body, ErrorResponseDto::class.java)
		return Pair(null, errorBody)
	}

	fun getFormTranslations(formPath: String): FormsApiResponse<List<FormTranslationDto>> {
		val responseType = object : ParameterizedTypeReference<List<FormTranslationDto>>() {}
		val response = restTemplate.exchange(
			"$baseUrl/v1/forms/$formPath/translations",
			HttpMethod.GET,
			null,
			responseType
		)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	fun publishGlobalTranslations(authToken: String?): FormsApiResponse<Unit> {
		val response = restTemplate.exchange(
			"$baseUrl/v1/global-translations/publish",
			HttpMethod.POST,
			HttpEntity(null, httpHeaders(authToken)),
			String::class.java
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(null, null)
			else -> Pair(null, readErrorBody(response))
		}
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedGlobalTranslations(languageCodeValue: String): FormsApiResponse<Map<String, String>> {
		val responseType = object : ParameterizedTypeReference<Map<String, String>>() {}
		val response = restTemplate.exchange(
			"$baseUrl/v1/published-global-translations/$languageCodeValue",
			HttpMethod.GET,
			null,
			responseType
		)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	fun getGlobalTranslationPublication(languageCodeValues: List<String>? = emptyList()): FormsApiResponse<PublishedTranslationsDto> {
		val queryString = if (languageCodeValues != null && !languageCodeValues.isEmpty()) "?languageCodes=${
			languageCodeValues.joinToString(",")
		}" else ""
		val response = restTemplate.exchange(
			"$baseUrl/v1/published-global-translations$queryString",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null)),
			String::class.java
		)
		val body = parsePublishedTranslationsResponse(response)
		return FormsApiResponse(response.statusCode, body)
	}

	private fun parsePublishedTranslationsResponse(response: ResponseEntity<String>): Pair<PublishedTranslationsDto?, ErrorResponseDto?> {
		if (response.statusCode.is2xxSuccessful) {
			val body = objectMapper.readValue(
				response.body,
				PublishedTranslationsDto::class.java
			)
			return Pair(body, null)
		}
		val errorBody = objectMapper.readValue(response.body, ErrorResponseDto::class.java)
		return Pair(null, errorBody)
	}

	fun deleteFormTranslation(
		formPath: String,
		formTranslationId: Long,
		authToken: String? = null
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange(
			"$baseUrl/v1/forms/$formPath/translations/$formTranslationId",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken)),
			String::class.java
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(null, null)
			else -> Pair(null, readErrorBody(response))
		}
		return FormsApiResponse(response.statusCode, body)
	}

	private fun readFormBody(response: ResponseEntity<String>): Pair<FormDto?, ErrorResponseDto?> {
		if (response.statusCode.is2xxSuccessful) {
			val body = objectMapper.readValue(
				response.body,
				FormDto::class.java
			)
			return Pair(body, null)
		}
		val errorBody = objectMapper.readValue(response.body, ErrorResponseDto::class.java)
		return Pair(null, errorBody)
	}


	private val formsBaseUrl = "$baseUrl/v1/forms"

	fun createForm(
		request: NewFormRequest,
		authToken: String? = null,
		additionalHeaders: Map<String, String> = emptyMap()
	): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange(
			formsBaseUrl,
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken, additionalHeaders)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun updateForm(
		formPath: String,
		revision: Int,
		request: UpdateFormRequest,
		authToken: String? = null,
		additionalHeaders: Map<String, String> = emptyMap()
	): FormsApiResponse<FormDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to revision.toString())
		val response = restTemplate.exchange(
			"$formsBaseUrl/$formPath",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers.plus(additionalHeaders))),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getForm(formPath: String, includeDeleted: Boolean? = false, select: String? = null): FormsApiResponse<FormDto> {
		val queryString = buildString {
			if (!select.isNullOrEmpty()) append("?select=$select")
			if (includeDeleted == true) append("${if (isNotEmpty()) "&" else "?"}includeDeleted=true")
		}
		val response = restTemplate.exchange(
			"$formsBaseUrl/$formPath$queryString",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteForm(formPath: String, formRevision: Int? = null, authToken: String?): FormsApiResponse<Unit> {
		val headers = when {
			formRevision !== null -> mapOf(formsapiEntityRevisionHeaderName to formRevision.toString())
			else -> emptyMap()
		}
		val response = restTemplate.exchange(
			"$formsBaseUrl/$formPath",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken, headers)),
			String::class.java
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(null, null)
			else -> Pair(null, readErrorBody(response))
		}
		return FormsApiResponse(response.statusCode, body)
	}

	fun getForms(select: String? = "", includeDeleted: Boolean? = false): FormsApiResponse<List<FormCompactDto>> {
		val queryString = buildString {
				if (!select.isNullOrEmpty()) append("?select=$select")
				if (includeDeleted == true) append("${if (isNotEmpty()) "&" else "?"}includeDeleted=true")
		}
		val responseType = object : ParameterizedTypeReference<List<FormCompactDto>>() {}
		val response = restTemplate.exchange("$formsBaseUrl${queryString}", HttpMethod.GET, null, responseType)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	private val formPublicationsBaseUrl = "$baseUrl/v1/form-publications"

	fun publishForm(
		formPath: String,
		formRevision: Int,
		authToken: String?,
		languageCodes: List<LanguageCode>? = null,
		skipTranslations: Boolean = false,
	): FormsApiResponse<FormDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to formRevision.toString())
		val queryString = when {
			skipTranslations -> "?skipTranslations=true"
			languageCodes?.isNotEmpty() == true -> languageCodes.let { "?languageCodes=${it.joinToString(",") { it.name }}" }
			else -> ""
		}
		val response = restTemplate.exchange(
			"$formPublicationsBaseUrl/$formPath${queryString}",
			HttpMethod.POST,
			HttpEntity(null, httpHeaders(authToken, headers)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun unpublishForm(
		formPath: String,
		authToken: String?,
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange(
			"$formPublicationsBaseUrl/$formPath",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken)),
			String::class.java
		)
		val body = if (!response.statusCode.is2xxSuccessful) Pair(null, readErrorBody(response)) else Pair(null, null)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedForm(formPath: String): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange(
			"$formPublicationsBaseUrl/$formPath",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedFormTranslations(
		formPath: String,
		languageCodes: List<LanguageCode>? = null,
	): FormsApiResponse<PublishedTranslationsDto> {
		val queryString = languageCodes?.let { "?languageCodes=${it.joinToString(",") { it.name }}" } ?: ""
		val response = restTemplate.exchange(
			"$formPublicationsBaseUrl/$formPath/translations${queryString}",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null)),
			String::class.java
		)
		val body = parsePublishedTranslationsResponse(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedForms(): FormsApiResponse<List<FormCompactDto>> {
		val responseType = object : ParameterizedTypeReference<List<FormCompactDto>>() {}
		val response = restTemplate.exchange(formPublicationsBaseUrl, HttpMethod.GET, null, responseType)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	fun lockForm(formPath: String, request: LockFormRequest, authToken: String?) : FormsApiResponse<FormDto> {
		val response = restTemplate.exchange(
			"$formsBaseUrl/$formPath/lock",
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

	fun unlockForm(formPath: String, authToken: String?) : FormsApiResponse<FormDto> {
		val response = restTemplate.exchange(
			"$formsBaseUrl/$formPath/lock",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken)),
			String::class.java
		)
		val body = readFormBody(response)
		return FormsApiResponse(response.statusCode, body)
	}

}
