package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import no.nav.forms.exceptions.db.DbError
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.LockFormRequest
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.MOCK_USER_GROUP_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.reflect.full.memberProperties

class EditFormsControllerTest : ApplicationTest(setupPublishedGlobalTranslations = true) {

	@Test
	fun testCreateForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val tema = "AAP"
		val innsending = "KUN_PAPIR"
		val request = FormsTestdata.newFormRequest(
			skjemanummer = "NAV 12-45.04B",
			properties = mapOf("tema" to tema, "innsending" to innsending),
			components = listOf(mapOf("type" to "panel"))
		)
		val newForm = testFormsApi.createForm(request, authToken)
			.assertSuccess()
			.body
		assertEquals(request.title, newForm.title)
		assertEquals("nav124504b", newForm.path)

		assertEquals(request.components.size, newForm.components?.size)
		assertEquals("panel", newForm.components?.get(0)["type"])
		assertEquals(tema, newForm.properties?.get("tema"))
		assertEquals(innsending, newForm.properties?.get("innsending"))
	}

	@Test
	fun testCreateFormFailureWhenMissingAuthToken() {
		val request = FormsTestdata.newFormRequest()
		val errorBody = testFormsApi.createForm(request, authToken = null)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
			.errorBody
		assertEquals(errorBody.errorMessage, "Unauthorized")
	}

	@Test
	fun testCreateFormWithSkjemanummerThatAlreadyExists() {
		val authToken = mockOAuth2Server.createMockToken()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TST123"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TST123"), authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TST 123"), authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
	}

	@Test
	fun testCreateFormWithSkjemanummerOfDeletedForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val skjemanummer = "TST123"
		val newForm = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = skjemanummer), authToken)
			.assertSuccess()
			.body
		testFormsApi.deleteForm(newForm.path!!, newForm.revision!!, authToken).assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = skjemanummer), authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
	}

	@Test
	fun testSkjemanummerAndTitleTrimOnCreate() {
		val authToken = mockOAuth2Server.createMockToken()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = " TST123  ", title = "  My form "), authToken)
			.assertSuccess().body.let {
				assertEquals("TST123", it.skjemanummer)
				assertEquals("My form", it.title)
			}
	}

	@Test
	fun testUpdateForm() {
		val createRequest = FormsTestdata.newFormRequest(
			properties = mapOf("tema" to "BIL")
		)
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		assertEquals(1, form.revision)
		assertEquals("BIL", form.properties?.get("tema"))
		assertEquals(createRequest.title, form.title)
		assertEquals(createRequest.components.size, form.components?.size)

		val updateRequest = FormsTestdata.updateFormRequest(
			title = form.title!!,
			components = form.components!!,
			properties = mapOf("tema" to "AAP")
		)
		val updatedForm = testFormsApi.updateForm(form.path!!, form.revision!!, updateRequest, authToken)
			.assertSuccess()
			.body
		assertEquals(2, updatedForm.revision)
		assertEquals("AAP", updatedForm.properties?.get("tema"))
		assertEquals(createRequest.title, updatedForm.title)
		assertEquals(createRequest.components.size, updatedForm.components?.size)
	}

	@Test
	fun testPartialUpdateOfForm() {
		val createRequest = FormsTestdata.newFormRequest(
			title = "Bilsøknad",
			properties = mapOf("tema" to "BIL"),
			components = listOf(
				mapOf("type" to "panel", "title" to "Panel 1"),
				mapOf("type" to "panel", "title" to "Panel 2"),
				mapOf("type" to "panel", "title" to "Panel 3"),
			),
		)
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		assertEquals(1, form.revision)
		assertEquals("BIL", form.properties?.get("tema"))
		assertEquals("Bilsøknad", form.title)
		assertEquals(3, form.components?.size)

		val updateRequest = FormsTestdata.updateFormRequest(
			title = null,
			components = null,
			properties = mapOf("tema" to "AAP")
		)
		val updatedForm = testFormsApi.updateForm(form.path!!, form.revision!!, updateRequest, authToken)
			.assertSuccess()
			.body
		assertEquals(2, updatedForm.revision)
		assertEquals("AAP", updatedForm.properties?.get("tema")) // only property updated
		assertEquals("Bilsøknad", updatedForm.title)
		assertEquals(3, updatedForm.components?.size)
	}

	@Test
	fun testUpdateFormFailureWhenWrongRevision() {
		val createRequest = FormsTestdata.newFormRequest(
			properties = mapOf("tema" to "BIL")
		)
		val authToken = mockOAuth2Server.createMockToken()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		assertEquals(1, newForm.revision)
		assertEquals("BIL", newForm.properties?.get("tema"))

		val newFormTitle = newForm.title!!
		val newFormComponents = newForm.components!!
		val newFormInitialRevision = newForm.revision!!

		val updateRequest1 = FormsTestdata.updateFormRequest(
			title = newFormTitle,
			components = newFormComponents,
			properties = mapOf("tema" to "AAP")
		)
		val updatedForm1 = testFormsApi.updateForm(newForm.path!!, newFormInitialRevision, updateRequest1, authToken)
			.assertSuccess()
			.body
		assertEquals(2, updatedForm1.revision)
		assertEquals("AAP", updatedForm1.properties?.get("tema"))

		val updateRequest2 = FormsTestdata.updateFormRequest(
			title = newFormTitle,
			components = newFormComponents,
			properties = mapOf("tema" to "PEN")
		)
		// Run second update with same revision as last update, which should fail with 409 Conflict
		val errorBody = testFormsApi.updateForm(newForm.path!!, newFormInitialRevision, updateRequest2, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
			.errorBody
		assertEquals("Conflict", errorBody.errorMessage)

		// Verify that the second update did not affect the form
		val currentForm = testFormsApi.getForm(newForm.path!!)
			.assertSuccess()
			.body
		assertEquals("AAP", currentForm.properties?.get("tema"))
	}

	@Test
	fun testUpdateOfDeletedForm() {
		val createRequest = FormsTestdata.newFormRequest()
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body

		testFormsApi.deleteForm(form.path!!, form.revision!!, authToken)
			.assertHttpStatus(HttpStatus.NO_CONTENT)

		val updateRequest = FormsTestdata.updateFormRequest(
			title = null,
			components = null,
			properties = mapOf("tema" to "AAP")
		)
		testFormsApi.updateForm(form.path!!, form.revision!!, updateRequest, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody.let {
				assertEquals("Form not found", it.errorMessage)
			}
	}

	@Test
	fun testGetForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val form = testFormsApi.getForm(newForm.path!!)
			.assertSuccess()
			.body
		assertEquals(newForm.title, form.title)
		assertEquals(newForm.components, form.components)
		assertEquals(newForm.properties, form.properties)
		assertEquals(newForm.revision, form.revision)
	}

	@Test
	fun testGetAllForms() {
		val authToken = mockOAuth2Server.createMockToken()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST1"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST2"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST3"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST4"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST5"), authToken)
			.assertSuccess()
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = "TEST6"), authToken)
			.assertSuccess()
		val forms = testFormsApi.getForms().assertSuccess().body
		assertEquals(6, forms.size)
	}

	fun assertPrecenceOfProps(formDto: FormCompactDto, select: List<String>) {
		for (prop in FormCompactDto::class.memberProperties) {
			// id should always be present
			if (prop.name == "id" || select.contains(prop.name)) {
				assertNotNull(prop.getter.call(formDto), "Expected ${prop.name} not to be null")
			} else {
				assertNull(prop.getter.call(formDto), "Expected ${prop.name} to be null")
			}
		}
	}

	@Test
	fun testGetAllFormsWithSelect() {
		val authToken = mockOAuth2Server.createMockToken()
		testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body

		val select1 = "properties"
		assertPrecenceOfProps(
			testFormsApi.getForms(select1).assertSuccess().body.first(),
			select1.split(",")
		)

		val select2 = "title,skjemanummer,changedAt"
		assertPrecenceOfProps(
			testFormsApi.getForms(select2).assertSuccess().body.first(),
			select2.split(",")
		)
	}

	@Test
	fun testLockForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!
		val formRevision = form.revision!!

		val lockReason = "Ingen skal endre dette skjemaet!"
		val lockRequest = LockFormRequest(lockReason)
		testFormsApi.lockForm(formPath, lockRequest, authToken)
			.assertSuccess().body.let {
				assertNotNull(it.lock?.createdAt)
				assertNotNull(it.lock?.createdBy)
				assertEquals(lockReason, it.lock?.reason)
			}

		val newTitle = "Ny tittel"
		testFormsApi.updateForm(formPath, formRevision, FormsTestdata.updateFormRequest(title = newTitle), authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)

		testFormsApi.getForm(formPath)
			.assertSuccess().body.let {
				assertNotEquals(newTitle, it.title)
			}

		testFormsApi.getForms("title,lock")
			.assertSuccess().body.let {
				assertEquals(1, it.size)
				assertNotEquals(newTitle, it[0].title)
				assertNotNull(it[0].lock)
			}

		testFormsApi.unlockForm(formPath, authToken)
			.assertSuccess().body.let {
				assertNull(it.lock)
			}

		testFormsApi.updateForm(formPath, formRevision, FormsTestdata.updateFormRequest(title = newTitle), authToken)
			.assertSuccess().body.let {
				assertEquals(newTitle, it.title)
			}

		testFormsApi.getForm(formPath)
			.assertSuccess().body.let {
				assertEquals(newTitle, it.title)
			}
	}

	@Test
	fun testDeleteFormWithoutAuthToken() {
		val formPath = "nav123456"
		val errorBody = testFormsApi.deleteForm(formPath, 1, authToken = null)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
			.errorBody
		assertEquals("Unauthorized", errorBody.errorMessage )
	}

	@Test
	fun testDeleteFormWithTokenWithNoAdminUser() {
		val authToken = mockOAuth2Server.createMockToken(groups = listOf(MOCK_USER_GROUP_ID))
		val formPath = "nav123456"
		val errorBody = testFormsApi.deleteForm(formPath, 1, authToken)
			.assertHttpStatus(HttpStatus.FORBIDDEN)
			.errorBody
		assertEquals("Forbidden", errorBody.errorMessage)
	}

	@Test
	fun testDeleteFormThatDoNotExist() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = "nav123456"
		testFormsApi.deleteForm(formPath, 1, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody.let {
				assertEquals("Form not found", it.errorMessage)
			}
	}

	@Test
	fun testDeleteForm() {
		val userName = "Testy Testesen"
		val authToken = mockOAuth2Server.createMockToken(userName = userName)
		val formPath = "nav123456"
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = formPath), authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, 1, authToken)
			.assertHttpStatus(HttpStatus.NO_CONTENT)
		testFormsApi.getForms("path,deletedAt,deletedBy")
			.assertSuccess().body.let {
				assertEquals(0, it.size)
			}
		testFormsApi.getForms("path,deletedAt,deletedBy", includeDeleted = true)
			.assertSuccess().body.let {
				assertEquals(1, it.size)
				assertNotNull(it[0].deletedAt)
				assertEquals(userName, it[0].deletedBy)
			}
		testFormsApi.getForm(formPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody.let {
				assertEquals("Form not found", it.errorMessage)
			}
		testFormsApi.getForm(formPath, includeDeleted = true)
			.assertSuccess()
			.body.let {
				assertNotNull(it.deletedAt)
				assertEquals(userName, it.deletedBy)
			}
	}

	@Test
	fun testDeleteFormWithWrongRevision() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = "nav123456"
		val firstRevisionOfForm = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = formPath), authToken)
			.assertSuccess()
			.body
		testFormsApi.updateForm(formPath, 1, FormsTestdata.updateFormRequest(title = "New title"), authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, firstRevisionOfForm.revision!!, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
			.errorBody.let {
				assertEquals("Conflict", it.errorMessage)
			}
	}

	@Test
	fun failsOnSecondDelete() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = "nav123456"
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = formPath), authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, 1, authToken)
			.assertHttpStatus(HttpStatus.NO_CONTENT)
		testFormsApi.deleteForm(formPath, 1, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody.let {
				assertEquals("Form not found", it.errorMessage)
			}
	}

	@Test
	fun testDeleteFormWithoutRevision() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = "nav123456"
		testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = formPath), authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, formRevision = null, authToken)
			.assertHttpStatus(HttpStatus.BAD_REQUEST)
			.errorBody.let {
				assertTrue(it.errorMessage.matches(Regex("Required request header .* is not present")))
			}
	}

	@Test
	fun shouldFailToDeleteFormWhenItHasBeenPublished() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = "nav123456"
		val newForm = testFormsApi.createForm(FormsTestdata.newFormRequest(skjemanummer = formPath), authToken)
			.assertSuccess()
			.body
		testFormsApi.getForm(formPath).assertSuccess()
		val formRevision = newForm.revision!!

		// verify published form
		testFormsApi.publishForm(formPath, formRevision, authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, formRevision, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
			.errorBody.let {
				assertEquals(DbError.FORMSAPI_002.message, it.errorMessage)
			}

		// verify unpublished form
		testFormsApi.unpublishForm(formPath, authToken)
			.assertSuccess()
		testFormsApi.deleteForm(formPath, formRevision, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
			.errorBody.let {
				assertEquals(DbError.FORMSAPI_002.message, it.errorMessage)
			}
	}

}
