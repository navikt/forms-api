package no.nav.forms

import no.nav.forms.model.DatabaseCleanupJob
import no.nav.forms.model.DatabaseCleanupPreview
import no.nav.forms.model.DatabaseCleanupRequest
import no.nav.forms.model.DatabaseCleanupStartRequest
import no.nav.forms.model.DatabaseCleanupStarted
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

class TestDatabaseCleanupApi(
    baseUrl: String,
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val url = "$baseUrl/api/database-cleanup"

    fun preview(request: DatabaseCleanupRequest, token: String): FormsApiResponse<DatabaseCleanupPreview> =
        exchange("$url/preview", HttpMethod.POST, request, token, DatabaseCleanupPreview::class.java)

    fun start(request: DatabaseCleanupStartRequest, token: String): FormsApiResponse<DatabaseCleanupStarted> =
        exchange("$url/jobs", HttpMethod.POST, request, token, DatabaseCleanupStarted::class.java)

    fun startRaw(json: String, token: String): FormsApiResponse<DatabaseCleanupStarted> =
        exchange("$url/jobs", HttpMethod.POST, json, token, DatabaseCleanupStarted::class.java)

    fun active(token: String): FormsApiResponse<DatabaseCleanupJob> =
        exchange("$url/jobs/active", HttpMethod.GET, null, token, DatabaseCleanupJob::class.java)

    fun status(id: String, token: String): FormsApiResponse<DatabaseCleanupJob> =
        exchange("$url/jobs/$id", HttpMethod.GET, null, token, DatabaseCleanupJob::class.java)

    private fun <T> exchange(
        path: String, method: HttpMethod, request: Any?, token: String, responseType: Class<T>
    ): FormsApiResponse<T> {
        val headers = apiHeaders(token).apply { contentType = MediaType.APPLICATION_JSON }
        val response = restTemplate.exchange<String>(path, method, HttpEntity(request, headers))
        return FormsApiResponse(response.statusCode, parseSingleResponse(response, responseType, objectMapper))
    }
}
