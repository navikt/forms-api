package no.nav.forms

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.forms.model.*
import no.nav.forms.testutils.FileUtils
import no.nav.forms.utils.LanguageCode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
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
		val response = restTemplate.exchange<String>(
			globalTranslationBaseUrl,
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken, additionalHeaders))
		)
		val body = parseSingleResponse(response, GlobalTranslationDto::class.java)
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
		val response = restTemplate.exchange<String>(
			"$globalTranslationBaseUrl/$id",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers.plus(additionalHeaders)))
		)
		val body = parseSingleResponse(response, GlobalTranslationDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteGlobalTranslation(
		id: Long,
		authToken: String? = null,
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange<String>(
			"$globalTranslationBaseUrl/$id",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = if (!response.statusCode.is2xxSuccessful) Pair(null, readErrorBody(response)) else Pair(null, null)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getGlobalTranslations(): FormsApiResponse<List<GlobalTranslationDto>> {
		val responseType = object : ParameterizedTypeReference<List<GlobalTranslationDto>>() {}
		val response = restTemplate.exchange(globalTranslationBaseUrl, HttpMethod.GET, null, responseType)
		return FormsApiResponse(response.statusCode, Pair(response.body!!, null))
	}

	private fun readErrorBody(response: ResponseEntity<String>): ErrorResponseDto {
		return objectMapper.readValue(response.body, ErrorResponseDto::class.java)
	}

	private fun httpHeaders(
		token: String?,
		additionalHeaders: Map<String, String>? = emptyMap()
	): MultiValueMap<String, String> {
		val headers = HttpHeaders()
		token?.let { headers.add("Authorization", "Bearer $it") }
		additionalHeaders?.forEach { headers.add(it.key, it.value) }
		return headers
	}

	fun createFormTranslation(
		formPath: String,
		request: NewFormTranslationRequestDto,
		authToken: String? = null
	): FormsApiResponse<FormTranslationDto> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/translations",
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken))
		)
		val body = parseSingleResponse(response, FormTranslationDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun updateFormTranslation(
		formPath: String,
		formTranslationId: Long,
		revision: Int,
		request: UpdateFormTranslationRequest,
		authToken: String? = null,
	): FormsApiResponse<FormTranslationDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to revision.toString())
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/translations/$formTranslationId",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers))
		)
		val body = parseSingleResponse(response, FormTranslationDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getFormTranslations(formPath: String): FormsApiResponse<List<FormTranslationDto>> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/translations",
			HttpMethod.GET,
			null
		)
		val body = parseListResponse(response, FormTranslationDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun publishGlobalTranslations(authToken: String?): FormsApiResponse<Unit> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/global-translations/publish",
			HttpMethod.POST,
			HttpEntity(null, httpHeaders(authToken))
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
		val queryString = if (!languageCodeValues.isNullOrEmpty()) "?languageCodes=${
			languageCodeValues.joinToString(",")
		}" else ""
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/published-global-translations$queryString",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null))
		)
		val body = parseSingleResponse(response, PublishedTranslationsDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteFormTranslation(
		formPath: String,
		formTranslationId: Long,
		authToken: String? = null
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/translations/$formTranslationId",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(null, null)
			else -> Pair(null, readErrorBody(response))
		}
		return FormsApiResponse(response.statusCode, body)
	}

	private val formsBaseUrl = "$baseUrl/v1/forms"

	fun createForm(
		request: NewFormRequest,
		authToken: String? = null,
		additionalHeaders: Map<String, String> = emptyMap()
	): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange<String>(
			formsBaseUrl,
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken, additionalHeaders))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
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
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath",
			HttpMethod.PUT,
			HttpEntity(request, httpHeaders(authToken, headers.plus(additionalHeaders)))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getForm(formPath: String, includeDeleted: Boolean? = false, select: String? = null): FormsApiResponse<FormDto> {
		val queryString = buildString {
			if (!select.isNullOrEmpty()) append("?select=$select")
			if (includeDeleted == true) append("${if (isNotEmpty()) "&" else "?"}includeDeleted=true")
		}
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath$queryString",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteForm(formPath: String, formRevision: Int? = null, authToken: String?): FormsApiResponse<Unit> {
		val headers = when {
			formRevision !== null -> mapOf(formsapiEntityRevisionHeaderName to formRevision.toString())
			else -> emptyMap()
		}
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken, headers))
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
		val response = restTemplate.exchange<String>("$formsBaseUrl${queryString}", HttpMethod.GET, null)
		val body = parseListResponse(response, FormCompactDto::class.java)
		return FormsApiResponse(response.statusCode, body)
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
		val response = restTemplate.exchange<String>(
			"$formPublicationsBaseUrl/$formPath${queryString}",
			HttpMethod.POST,
			HttpEntity(null, httpHeaders(authToken, headers))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun unpublishForm(
		formPath: String,
		authToken: String?,
	): FormsApiResponse<Unit> {
		val response = restTemplate.exchange<String>(
			"$formPublicationsBaseUrl/$formPath",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = if (!response.statusCode.is2xxSuccessful) Pair(null, readErrorBody(response)) else Pair(null, null)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedForm(formPath: String): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange<String>(
			"$formPublicationsBaseUrl/$formPath",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedFormTranslations(
		formPath: String,
		languageCodes: List<LanguageCode>? = null,
	): FormsApiResponse<PublishedTranslationsDto> {
		val queryString = languageCodes?.let { "?languageCodes=${it.joinToString(",") { it.name }}" } ?: ""
		val response = restTemplate.exchange<String>(
			"$formPublicationsBaseUrl/$formPath/translations${queryString}",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null))
		)
		val body = parseSingleResponse(response, PublishedTranslationsDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getPublishedForms(): FormsApiResponse<List<FormCompactDto>> {
		val response = restTemplate.exchange<String>(formPublicationsBaseUrl, HttpMethod.GET, null)
		val body = parseListResponse(response, FormCompactDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun lockForm(formPath: String, request: LockFormRequest, authToken: String?): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath/lock",
			HttpMethod.POST,
			HttpEntity(request, httpHeaders(authToken))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun unlockForm(formPath: String, authToken: String?): FormsApiResponse<FormDto> {
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath/lock",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun getStaticPdfs(formPath: String): FormsApiResponse<List<StaticPdfDto>> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/static-pdfs",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(null))
		)
		val body = parseListResponse(response, StaticPdfDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun uploadStaticPdf(
		formPath: String,
		languageCode: String,
		fileName: String = "small.pdf",
		authToken: String? = null,
	): FormsApiResponse<StaticPdfDto> {
		val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
		authToken?.let { headers.add(HttpHeaders.AUTHORIZATION, "Bearer $it") }

		val partHeaders = HttpHeaders().apply {
			contentType = MediaType.APPLICATION_PDF
			setContentDispositionFormData("fileContent", fileName)
		}
		val filePart = HttpEntity(FileUtils.loadBytes(fileName), partHeaders)
		val requestBody: MultiValueMap<String, Any> = LinkedMultiValueMap<String, Any>().apply {
			add("fileContent", filePart)
		}
		val httpEntity = HttpEntity(requestBody, headers)

		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/static-pdfs/$languageCode",
			HttpMethod.POST,
			httpEntity
		)
		val body = parseSingleResponse(response, StaticPdfDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	fun deleteStaticPdf(formPath: String, languageCode: String, authToken: String? = null): FormsApiResponse<Unit> {
		val response = restTemplate.exchange<String>(
			"$baseUrl/v1/forms/$formPath/static-pdfs/$languageCode",
			HttpMethod.DELETE,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(null, null)
			else -> Pair(null, readErrorBody(response))
		}
		return FormsApiResponse(response.statusCode, body)
	}

	fun getStaticPdfContent(
		formPath: String,
		languageCode: String,
		authToken: String? = null,
	): FormsApiResponse<ByteArray> {
		val response = restTemplate.exchange<ByteArray>(
			"$baseUrl/v1/forms/$formPath/static-pdfs/$languageCode",
			HttpMethod.GET,
			HttpEntity(null, httpHeaders(authToken))
		)
		val body = when {
			response.statusCode.is2xxSuccessful -> Pair(response.body, null)
			else -> Pair(
				null,
				readErrorBody(ResponseEntity(String(response.body ?: ByteArray(0)), response.headers, response.statusCode))
			)
		}
		return FormsApiResponse(response.statusCode, body)
	}

	fun resetForm(
		formPath: String,
		revision: Int?,
		authToken: String?,
	): FormsApiResponse<FormDto> {
		val headers = mapOf(formsapiEntityRevisionHeaderName to revision.toString())
		val response = restTemplate.exchange<String>(
			"$formsBaseUrl/$formPath/reset",
			HttpMethod.POST,
			HttpEntity(null, httpHeaders(authToken, headers))
		)
		val body = parseSingleResponse(response, FormDto::class.java)
		return FormsApiResponse(response.statusCode, body)
	}

	private fun <T> parseListResponse(
		response: ResponseEntity<String>,
		clazz: Class<T>
	): Pair<List<T>?, ErrorResponseDto?> =
		when {
			response.statusCode.is2xxSuccessful -> Pair(
				objectMapper.readValue(
					response.body,
					objectMapper.typeFactory.constructCollectionType(List::class.java, clazz)
				), null
			)

			else -> Pair(null, objectMapper.readValue(response.body, ErrorResponseDto::class.java))
		}

	private fun <T> parseSingleResponse(response: ResponseEntity<String>, clazz: Class<T>): Pair<T?, ErrorResponseDto?> =
		when {
			response.statusCode.is2xxSuccessful -> Pair(objectMapper.readValue(response.body, clazz), null)
			else -> Pair(null, objectMapper.readValue(response.body, ErrorResponseDto::class.java))
		}

}
