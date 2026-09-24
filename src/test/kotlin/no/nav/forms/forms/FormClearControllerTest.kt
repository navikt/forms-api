package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import no.nav.forms.model.LockFormRequest
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.createUserToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.boot.resttestclient.exchange

class FormClearControllerTest : ApplicationTest() {
    private fun headers(token: String) = HttpHeaders().apply { setBearerAuth(token) }
    private fun confirmed(request: FormClearRequest, plan: FormClearPreview) =
        FormClearStartRequest(request.keepTestForms, request.keepLockedForms, request.keepFormPaths, plan.toDelete, plan.kept)

    @Test
    fun `preview and status require admin and return the resolved plan`() {
        val admin = mockOAuth2Server.createMockToken()
        val user = mockOAuth2Server.createUserToken()
        val form = testFormsApi.createForm(
            FormsTestdata.newFormRequest(skjemanummer = "CLR100"), admin
        ).assertSuccess().body
        val request = FormClearRequest(false, false, emptyList())
        val url = "$baseUrl/api/form-clear"

        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange<String>(
            "$url/preview", HttpMethod.POST, HttpEntity(request, headers(user))
        ).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, restTemplate.exchange<String>(
            "$url/jobs/active", HttpMethod.GET, HttpEntity(null, headers(admin))
        ).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange<String>(
            "$url/jobs/active", HttpMethod.GET, HttpEntity(null, headers(user))
        ).statusCode)
        val preview = restTemplate.exchange<FormClearPreview>(
            "$url/preview", HttpMethod.POST, HttpEntity(request, headers(admin))
        )
        assertEquals(HttpStatus.OK, preview.statusCode)
        assertEquals(listOf(form.path), preview.body!!.toDelete)
        assertTrue(preview.body!!.kept.isEmpty())

        val started = restTemplate.exchange<FormClearStarted>(
            "$url/jobs", HttpMethod.POST, HttpEntity(confirmed(request, preview.body!!), headers(admin))
        )
        assertEquals(HttpStatus.CREATED, started.statusCode)
        assertEquals(HttpStatus.CONFLICT, restTemplate.exchange<String>(
            "$url/jobs", HttpMethod.POST, HttpEntity(confirmed(request, preview.body!!), headers(admin))
        ).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange<String>(
            "$url/jobs/${started.body!!.jobId}", HttpMethod.GET, HttpEntity(null, headers(user))
        ).statusCode)
        val status = restTemplate.exchange<FormClearJob>(
            "$url/jobs/${started.body!!.jobId}", HttpMethod.GET, HttpEntity(null, headers(admin))
        ).body!!
        assertEquals("pending", status.status)
        assertEquals(1, status.totalCount)
        assertEquals(0, status.processedCount)
        val active = restTemplate.exchange<FormClearJob>(
            "$url/jobs/active", HttpMethod.GET, HttpEntity(null, headers(admin))
        )
        assertEquals(HttpStatus.OK, active.statusCode)
        assertEquals(status, active.body)
    }

    @Test
    fun `start rejects missing expected lists and duplicate or invalid paths`() {
        val admin = mockOAuth2Server.createMockToken()
        val url = "$baseUrl/api/form-clear/jobs"
        val jsonHeaders = headers(admin).apply { contentType = MediaType.APPLICATION_JSON }
        assertEquals(HttpStatus.BAD_REQUEST, restTemplate.exchange<String>(
            url, HttpMethod.POST,
            HttpEntity("""{"keepTestForms":false,"keepLockedForms":false,"keepFormPaths":[]}""", jsonHeaders)
        ).statusCode)
        listOf(
            FormClearStartRequest(false, false, emptyList(), listOf("same", "same"), emptyList()),
            FormClearStartRequest(false, false, emptyList(), listOf("same"), listOf("same")),
            FormClearStartRequest(false, false, emptyList(), listOf(" "), emptyList()),
            FormClearStartRequest(false, false, emptyList(), listOf("x".repeat(25)), emptyList())
        ).forEach { request ->
            assertEquals(HttpStatus.BAD_REQUEST, restTemplate.exchange<String>(
                url, HttpMethod.POST, HttpEntity(request, headers(admin))
            ).statusCode)
        }
    }

    @Test
    fun `nested form path from preview can start a job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR/601"), admin)
            .assertSuccess().body
        assertEquals("clr/601", form.path)
        val request = FormClearRequest(false, false, emptyList())
        val plan = preview(request, admin)
        assertEquals(listOf(form.path), plan.toDelete)
        val started = restTemplate.exchange<FormClearStarted>(
            "$baseUrl/api/form-clear/jobs", HttpMethod.POST, HttpEntity(confirmed(request, plan), headers(admin))
        )
        assertEquals(HttpStatus.CREATED, started.statusCode)
        val status = restTemplate.exchange<FormClearJob>(
            "$baseUrl/api/form-clear/jobs/active", HttpMethod.GET, HttpEntity(null, headers(admin))
        ).body!!
        assertEquals(started.body!!.jobId, status.jobId)
        assertEquals(1, status.totalCount)
    }

    @Test
    fun `unlocking a kept form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val path = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR501"), admin).body.path!!
        testFormsApi.lockForm(path, LockFormRequest("Keep"), admin).assertSuccess()
        val request = FormClearRequest(false, true, emptyList())
        val plan = preview(request, admin)
        assertEquals(listOf(path), plan.kept)
        testFormsApi.unlockForm(path, admin).assertSuccess()
        assertStale(request, plan, admin)
    }

    @Test
    fun `locking a target form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val path = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR505"), admin).body.path!!
        val request = FormClearRequest(false, true, emptyList())
        val plan = preview(request, admin)
        assertEquals(listOf(path), plan.toDelete)
        testFormsApi.lockForm(path, LockFormRequest("Keep"), admin).assertSuccess()
        assertStale(request, plan, admin)
        val freshPlan = preview(request, admin)
        assertEquals(listOf(path), freshPlan.kept)
        val started = restTemplate.exchange<FormClearStarted>(
            "$baseUrl/api/form-clear/jobs", HttpMethod.POST, HttpEntity(confirmed(request, freshPlan), headers(admin))
        )
        assertEquals(HttpStatus.CREATED, started.statusCode)
    }

    @Test
    fun `changing test status after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(
            FormsTestdata.newFormRequest(skjemanummer = "CLR502", properties = mapOf("isTestForm" to true)), admin
        ).body
        val request = FormClearRequest(true, false, emptyList())
        val plan = preview(request, admin)
        assertEquals(listOf(form.path), plan.kept)
        testFormsApi.updateForm(
            form.path!!, form.revision!!, FormsTestdata.updateFormRequest(properties = mapOf("isTestForm" to false)), admin
        ).assertSuccess()
        assertStale(request, plan, admin)
    }

    @Test
    fun `adding a form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val request = FormClearRequest(false, false, emptyList())
        val plan = preview(request, admin)
        testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR503"), admin).assertSuccess()
        assertStale(request, plan, admin)
    }

    private fun preview(request: FormClearRequest, token: String) = restTemplate.exchange<FormClearPreview>(
        "$baseUrl/api/form-clear/preview", HttpMethod.POST, HttpEntity(request, headers(token))
    ).body!!

    private fun assertStale(request: FormClearRequest, plan: FormClearPreview, token: String) {
        assertEquals(HttpStatus.CONFLICT, restTemplate.exchange<String>(
            "$baseUrl/api/form-clear/jobs", HttpMethod.POST, HttpEntity(confirmed(request, plan), headers(token))
        ).statusCode)
    }
}
