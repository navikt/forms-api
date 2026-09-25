package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import no.nav.forms.model.DatabaseCleanupJob
import no.nav.forms.model.DatabaseCleanupJobItemsInner
import no.nav.forms.model.DatabaseCleanupPreview
import no.nav.forms.model.DatabaseCleanupRequest
import no.nav.forms.model.DatabaseCleanupStartRequest
import no.nav.forms.model.LockFormRequest
import no.nav.forms.model.NewFormTranslationRequestDto
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.createUserToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DatabaseCleanupControllerTest : ApplicationTest(setupPublishedGlobalTranslations = true) {
    @Autowired lateinit var clear: FormClearService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun request(keepTest: Boolean = false, keepLocked: Boolean = false, paths: List<String> = emptyList()) =
        DatabaseCleanupRequest(keepTest, keepLocked, paths)

    private fun confirmed(request: DatabaseCleanupRequest, plan: DatabaseCleanupPreview) =
        DatabaseCleanupStartRequest(
            request.keepTestForms, request.keepLockedForms, request.keepFormPaths, plan.toDelete, plan.kept
        )

    private fun preview(request: DatabaseCleanupRequest, token: String) =
        testDatabaseCleanupApi.preview(request, token).assertHttpStatus(HttpStatus.OK).body

    private fun start(request: DatabaseCleanupRequest, token: String): String =
        testDatabaseCleanupApi.start(confirmed(request, preview(request, token)), token)
            .assertHttpStatus(HttpStatus.CREATED).body.jobId

    private fun status(id: String, token: String) =
        testDatabaseCleanupApi.status(id, token).assertHttpStatus(HttpStatus.OK).body

    private fun assertStale(request: DatabaseCleanupRequest, plan: DatabaseCleanupPreview, token: String) {
        testDatabaseCleanupApi.start(confirmed(request, plan), token).assertHttpStatus(HttpStatus.CONFLICT)
    }

    private fun count(sql: String, vararg params: Any) = jdbc.queryForObject(sql, Int::class.java, *params)!!

    @Test
    fun `preview and status require admin and return the resolved plan`() {
        val admin = mockOAuth2Server.createMockToken()
        val user = mockOAuth2Server.createUserToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR100"), admin)
            .assertSuccess().body
        val options = request()

        testDatabaseCleanupApi.preview(options, user).assertHttpStatus(HttpStatus.FORBIDDEN)
        testDatabaseCleanupApi.active(admin).assertHttpStatus(HttpStatus.NOT_FOUND)
        testDatabaseCleanupApi.active(user).assertHttpStatus(HttpStatus.FORBIDDEN)
        testDatabaseCleanupApi.status("invalid-id", admin).assertHttpStatus(HttpStatus.BAD_REQUEST)
        val plan = preview(options, admin)
        assertEquals(listOf(form.path), plan.toDelete)
        assertTrue(plan.kept.isEmpty())

        val started = testDatabaseCleanupApi.start(confirmed(options, plan), admin)
            .assertHttpStatus(HttpStatus.CREATED).body
        testDatabaseCleanupApi.start(confirmed(options, plan), admin).assertHttpStatus(HttpStatus.CONFLICT)
        testDatabaseCleanupApi.status(started.jobId, user).assertHttpStatus(HttpStatus.FORBIDDEN)
        val job = status(started.jobId, admin)
        assertEquals(DatabaseCleanupJob.Status.pending, job.status)
        assertEquals(1, job.totalCount)
        assertEquals(0, job.processedCount)
        assertEquals(job, testDatabaseCleanupApi.active(admin).assertHttpStatus(HttpStatus.OK).body)
    }

    @Test
    fun `start rejects missing expected lists and duplicate or invalid paths`() {
        val admin = mockOAuth2Server.createMockToken()
        testDatabaseCleanupApi.startRaw(
            """{"keepTestForms":false,"keepLockedForms":false,"keepFormPaths":[]}""", admin
        ).assertHttpStatus(HttpStatus.BAD_REQUEST)
        listOf(
            DatabaseCleanupStartRequest(false, false, emptyList(), listOf("same", "same"), emptyList()),
            DatabaseCleanupStartRequest(false, false, emptyList(), listOf("same"), listOf("same")),
            DatabaseCleanupStartRequest(false, false, emptyList(), listOf(" "), emptyList()),
            DatabaseCleanupStartRequest(false, false, emptyList(), listOf("x".repeat(25)), emptyList())
        ).forEach { testDatabaseCleanupApi.start(it, admin).assertHttpStatus(HttpStatus.BAD_REQUEST) }
    }

    @Test
    fun `nested form path from preview can start a job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR/601"), admin)
            .assertSuccess().body
        assertEquals("clr/601", form.path)
        val options = request()
        val plan = preview(options, admin)
        assertEquals(listOf(form.path), plan.toDelete)
        val started = testDatabaseCleanupApi.start(confirmed(options, plan), admin)
            .assertHttpStatus(HttpStatus.CREATED).body
        val active = testDatabaseCleanupApi.active(admin).assertHttpStatus(HttpStatus.OK).body
        assertEquals(started.jobId, active.jobId)
        assertEquals(1, active.totalCount)
    }

    @Test
    fun `unlocking a kept form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val path = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR501"), admin).body.path!!
        testFormsApi.lockForm(path, LockFormRequest("Keep"), admin).assertSuccess()
        val options = request(keepLocked = true)
        val plan = preview(options, admin)
        assertEquals(listOf(path), plan.kept)
        testFormsApi.unlockForm(path, admin).assertSuccess()
        assertStale(options, plan, admin)
    }

    @Test
    fun `locking a target form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val path = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR505"), admin).body.path!!
        val options = request(keepLocked = true)
        val plan = preview(options, admin)
        assertEquals(listOf(path), plan.toDelete)
        testFormsApi.lockForm(path, LockFormRequest("Keep"), admin).assertSuccess()
        assertStale(options, plan, admin)
        val freshPlan = preview(options, admin)
        assertEquals(listOf(path), freshPlan.kept)
        testDatabaseCleanupApi.start(confirmed(options, freshPlan), admin).assertHttpStatus(HttpStatus.CREATED)
    }

    @Test
    fun `changing test status after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(
            FormsTestdata.newFormRequest(skjemanummer = "CLR502", properties = mapOf("isTestForm" to true)), admin
        ).body
        val options = request(keepTest = true)
        val plan = preview(options, admin)
        assertEquals(listOf(form.path), plan.kept)
        testFormsApi.updateForm(
            form.path!!, form.revision!!, FormsTestdata.updateFormRequest(properties = mapOf("isTestForm" to false)), admin
        ).assertSuccess()
        assertStale(options, plan, admin)
    }

    @Test
    fun `adding a form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val options = request()
        val plan = preview(options, admin)
        testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR503"), admin).assertSuccess()
        assertStale(options, plan, admin)
    }

    @Test
    fun `removing a form after preview prevents starting the job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR504"), admin).body
        val options = request()
        val plan = preview(options, admin)
        val formId = jdbc.queryForObject("SELECT id FROM form WHERE path = ?", Long::class.java, form.path)!!
        jdbc.update("DELETE FROM form_revision WHERE form_id = ?", formId)
        jdbc.update("DELETE FROM form WHERE id = ?", formId)
        assertStale(options, plan, admin)
        assertEquals(0, count("SELECT count(*) FROM form_clear_job"))
    }

    @Test
    fun `published forms and their snapshots are purged while protected forms remain untouched`() {
        val admin = mockOAuth2Server.createMockToken()
        val removed = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR101"), admin).body
        val test = testFormsApi.createForm(
            FormsTestdata.newFormRequest(skjemanummer = "CLR102", properties = mapOf("isTestForm" to true)), admin
        ).body
        val locked = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR103"), admin).body
        val named = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR104"), admin).body
        testFormsApi.createFormTranslation(
            removed.path!!, NewFormTranslationRequestDto(key = "heading", nb = "Heading"), admin
        ).assertSuccess()
        testFormsApi.publishForm(removed.path, removed.revision!!, admin).assertSuccess()
        testFormsApi.publishForm(test.path!!, test.revision!!, admin).assertSuccess()
        testFormsApi.lockForm(locked.path!!, LockFormRequest("Keep this form"), admin).assertSuccess()
        val keptPublication = count(
            "SELECT count(*) FROM form_publication WHERE form_id = (SELECT id FROM form WHERE path = ?)", test.path
        )
        val options = request(keepTest = true, keepLocked = true, paths = listOf(named.path!!))
        val plan = preview(options, admin)
        assertEquals(listOf(removed.path), plan.toDelete)
        assertEquals(setOf(test.path, locked.path, named.path), plan.kept.toSet())
        val id = start(options, admin)
        assertEquals(3, status(id, admin).keptCount)
        clear.processNextJob()
        val result = status(id, admin)
        assertEquals(DatabaseCleanupJob.Status.completed, result.status)
        assertEquals(4, result.totalCount)
        assertEquals(4, result.processedCount)
        assertEquals(1, result.deletedCount)
        assertEquals(3, result.keptCount)
        assertEquals(0, result.failedCount)
        testDatabaseCleanupApi.active(admin).assertHttpStatus(HttpStatus.NOT_FOUND)
        assertEquals(0, count("SELECT count(*) FROM form WHERE path = ?", removed.path))
        assertEquals(0, count("SELECT count(*) FROM form_revision WHERE form_id NOT IN (SELECT id FROM form)"))
        assertEquals(0, count("SELECT count(*) FROM form_translation WHERE form_id NOT IN (SELECT id FROM form)"))
        assertEquals(0, count("SELECT count(*) FROM form_translation_revision WHERE form_translation_id NOT IN (SELECT id FROM form_translation)"))
        assertEquals(0, count("SELECT count(*) FROM form_publication WHERE form_id NOT IN (SELECT id FROM form)"))
        assertEquals(0, count("SELECT count(*) FROM published_form_translation WHERE form_id NOT IN (SELECT id FROM form)"))
        assertEquals(0, count("SELECT count(*) FROM published_form_translation_revision WHERE published_form_translation_id NOT IN (SELECT id FROM published_form_translation)"))
        assertEquals(keptPublication, count(
            "SELECT count(*) FROM form_publication WHERE form_id = (SELECT id FROM form WHERE path = ?)", test.path
        ))
        assertEquals(3, count("SELECT count(*) FROM form"))
    }

    @Test
    fun `a failed form rolls back while the next form is deleted`() {
        val admin = mockOAuth2Server.createMockToken()
        val failed = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR201"), admin).body
        val deleted = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR202"), admin).body
        testFormsApi.publishForm(failed.path!!, failed.revision!!, admin).assertSuccess()
        jdbc.execute("CREATE TABLE clear_test_blocker (form_id bigint REFERENCES form(id))")
        jdbc.update("INSERT INTO clear_test_blocker SELECT id FROM form WHERE path = ?", failed.path)
        val id = start(request(), admin)
        clear.processNextJob()
        val result = status(id, admin)
        assertEquals(DatabaseCleanupJob.Status.completed, result.status)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.deletedCount)
        assertTrue(result.items.any {
            it.path == failed.path && it.outcome == DatabaseCleanupJobItemsInner.Outcome.failed
        })
        assertEquals(1, count("SELECT count(*) FROM form WHERE path = ?", failed.path))
        assertEquals(1, count(
            "SELECT count(*) FROM form_publication WHERE form_id = (SELECT id FROM form WHERE path = ?)", failed.path
        ))
        assertEquals(0, count("SELECT count(*) FROM form WHERE path = ?", deleted.path!!))
    }

    @Test
    fun `expired ownership resumes only remaining paths and purges soft deleted forms`() {
        val admin = mockOAuth2Server.createMockToken()
        val first = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR301"), admin).body
        val second = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR302"), admin).body
        testFormsApi.deleteForm(second.path!!, second.revision, admin).assertSuccess()
        val id = start(request(), admin)
        jdbc.update(
            "UPDATE form_clear_job SET status = 'running', owner = 'dead-pod', lease_until = now() - interval '1 minute' WHERE id = ?",
            java.util.UUID.fromString(id)
        )
        jdbc.update(
            "INSERT INTO form_clear_job_item (job_id, path, outcome) VALUES (?, ?, 'deleted')",
            java.util.UUID.fromString(id), first.path
        )
        assertEquals(DatabaseCleanupJob.Status.running, testDatabaseCleanupApi.active(admin).body.status)
        clear.processNextJob()
        val result = status(id, admin)
        assertEquals(DatabaseCleanupJob.Status.completed, result.status)
        assertEquals(2, result.deletedCount)
        assertEquals(1, count("SELECT count(*) FROM form WHERE path = ?", first.path!!))
        assertEquals(0, count("SELECT count(*) FROM form WHERE path = ?", second.path))
        assertFalse(result.items.any { it.outcome == DatabaseCleanupJobItemsInner.Outcome.failed })
        assertEquals(1, count("SELECT count(*) FROM form_clear_job_item WHERE job_id = ? AND path = ?",
            java.util.UUID.fromString(id), first.path))
    }

    @Test
    fun `two workers cannot claim and process the same pending job`() {
        val admin = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR401"), admin).body
        val id = start(request(), admin)
        val workers = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(1)
        try {
            val results = (1..2).map {
                workers.submit {
                    latch.await()
                    clear.processNextJob()
                }
            }
            latch.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(DatabaseCleanupJob.Status.completed, status(id, admin).status)
            assertEquals(1, status(id, admin).deletedCount)
            assertEquals(1, count("SELECT count(*) FROM form_clear_job_item WHERE job_id = ? AND path = ?",
                java.util.UUID.fromString(id), form.path!!))
        } finally {
            workers.shutdownNow()
        }
    }
}
