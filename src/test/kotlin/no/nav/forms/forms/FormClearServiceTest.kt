package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import no.nav.forms.exceptions.ConflictException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.model.LockFormRequest
import no.nav.forms.model.NewFormTranslationRequestDto
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FormClearServiceTest : ApplicationTest(setupPublishedGlobalTranslations = true) {
    @Autowired lateinit var clear: FormClearService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun count(sql: String, vararg params: Any) = jdbc.queryForObject(sql, Int::class.java, *params)!!
    private fun start(request: FormClearRequest): java.util.UUID {
        val plan = clear.preview(request)
        return clear.start(
            FormClearStartRequest(
                request.keepTestForms, request.keepLockedForms, request.keepFormPaths, plan.toDelete, plan.kept
            ), "A123456"
        )
    }

    @Test
    fun `published forms and their snapshots are purged while protected forms remain untouched`() {
        val token = mockOAuth2Server.createMockToken()
        val removed = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR101"), token).body
        val test = testFormsApi.createForm(
            FormsTestdata.newFormRequest(skjemanummer = "CLR102", properties = mapOf("isTestForm" to true)), token
        ).body
        val locked = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR103"), token).body
        val named = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR104"), token).body
        testFormsApi.createFormTranslation(
            removed.path!!, NewFormTranslationRequestDto(key = "heading", nb = "Heading"), token
        ).assertSuccess()
        testFormsApi.publishForm(removed.path, removed.revision!!, token).assertSuccess()
        testFormsApi.publishForm(test.path!!, test.revision!!, token).assertSuccess()
        testFormsApi.lockForm(locked.path!!, LockFormRequest("Keep this form"), token).assertSuccess()
        val keptPublication = count(
            "SELECT count(*) FROM form_publication WHERE form_id = (SELECT id FROM form WHERE path = ?)", test.path
        )
        val request = FormClearRequest(true, true, listOf(named.path!!))
        val plan = clear.preview(request)
        assertEquals(listOf(removed.path), plan.toDelete)
        assertEquals(setOf(test.path, locked.path, named.path), plan.kept.toSet())
        val id = start(request)
        assertEquals(3, clear.status(id).keptCount)
        clear.processNextJob()
        val result = clear.status(id)
        assertEquals("completed", result.status)
        assertEquals(4, result.totalCount)
        assertEquals(4, result.processedCount)
        assertEquals(1, result.deletedCount)
        assertEquals(3, result.keptCount)
        assertEquals(0, result.failedCount)
        assertThrows(ResourceNotFoundException::class.java) { clear.activeJob() }
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
        val token = mockOAuth2Server.createMockToken()
        val failed = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR201"), token).body
        val deleted = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR202"), token).body
        testFormsApi.publishForm(failed.path!!, failed.revision!!, token).assertSuccess()
        jdbc.execute("CREATE TABLE clear_test_blocker (form_id bigint REFERENCES form(id))")
        jdbc.update("INSERT INTO clear_test_blocker SELECT id FROM form WHERE path = ?", failed.path)
        val id = start(FormClearRequest(false, false, emptyList()))
        clear.processNextJob()
        val result = clear.status(id)
        assertEquals("completed", result.status)
        assertEquals(1, result.failedCount)
        assertEquals(1, result.deletedCount)
        assertTrue(result.items.any { it.path == failed.path && it.outcome == "failed" })
        assertEquals(1, count("SELECT count(*) FROM form WHERE path = ?", failed.path!!))
        assertEquals(1, count(
            "SELECT count(*) FROM form_publication WHERE form_id = (SELECT id FROM form WHERE path = ?)", failed.path
        ))
        assertEquals(0, count("SELECT count(*) FROM form WHERE path = ?", deleted.path!!))
    }

    @Test
    fun `expired ownership resumes only remaining paths and purges soft deleted forms`() {
        val token = mockOAuth2Server.createMockToken()
        val first = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR301"), token).body
        val second = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR302"), token).body
        testFormsApi.deleteForm(second.path!!, second.revision, token).assertSuccess()
        val id = start(FormClearRequest(false, false, emptyList()))
        jdbc.update(
            "UPDATE form_clear_job SET status = 'running', owner = 'dead-pod', lease_until = now() - interval '1 minute' WHERE id = ?",
            id
        )
        jdbc.update(
            "INSERT INTO form_clear_job_item (job_id, path, outcome) VALUES (?, ?, 'deleted')",
            id, first.path
        )
        assertEquals("running", clear.activeJob().status)
        clear.processNextJob()
        val result = clear.status(id)
        assertEquals("completed", result.status)
        assertEquals(2, result.deletedCount)
        assertEquals(1, count("SELECT count(*) FROM form WHERE path = ?", first.path!!))
        assertEquals(0, count("SELECT count(*) FROM form WHERE path = ?", second.path))
        assertFalse(result.items.any { it.outcome == "failed" })
        assertEquals(1, count("SELECT count(*) FROM form_clear_job_item WHERE job_id = ? AND path = ?", id, first.path))
    }

    @Test
    fun `two workers cannot claim and process the same pending job`() {
        val token = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR401"), token).body
        val id = start(FormClearRequest(false, false, emptyList()))
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                workers.submit {
                    start.await()
                    clear.processNextJob()
                }
            }
            start.countDown()
            results.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals("completed", clear.status(id).status)
            assertEquals(1, clear.status(id).deletedCount)
            assertEquals(1, count("SELECT count(*) FROM form_clear_job_item WHERE job_id = ? AND path = ?", id, form.path!!))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `a form removed after preview cannot start a job from that plan`() {
        val token = mockOAuth2Server.createMockToken()
        val form = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "CLR504"), token).body
        val request = FormClearRequest(false, false, emptyList())
        val plan = clear.preview(request)
        val formId = jdbc.queryForObject("SELECT id FROM form WHERE path = ?", Long::class.java, form.path)!!
        jdbc.update("DELETE FROM form_revision WHERE form_id = ?", formId)
        jdbc.update("DELETE FROM form WHERE id = ?", formId)
        assertThrows(ConflictException::class.java) {
            clear.start(
                FormClearStartRequest(false, false, emptyList(), plan.toDelete, plan.kept), "A123456"
            )
        }
        assertEquals(0, count("SELECT count(*) FROM form_clear_job"))
    }
}
