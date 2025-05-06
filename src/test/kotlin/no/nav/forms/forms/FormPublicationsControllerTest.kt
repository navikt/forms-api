package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import no.nav.forms.model.FormStatus
import no.nav.forms.model.NewFormTranslationRequestDto
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import no.nav.forms.utils.LanguageCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertFalse

class FormPublicationsControllerTest : ApplicationTest(setupPublishedGlobalTranslations = true) {

	@Test
	fun testPublishForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val formBeforePublication = testFormsApi.getForm(formPath)
			.assertSuccess()
			.body
		assertNull(formBeforePublication.publishedAt)
		assertNull(formBeforePublication.publishedBy)

		val publishedForm = testFormsApi.publishForm(formPath, formRevision, authToken)
			.assertSuccess()
			.body

		assertEquals(formPath, publishedForm.path)
		assertEquals(formRevision, publishedForm.revision)
		assertNotNull(publishedForm.publishedAt)
		assertNotNull(publishedForm.publishedBy)

		val formAfterPublication = testFormsApi.getForm(formPath)
			.assertSuccess()
			.body
		assertEquals(publishedForm.publishedAt, formAfterPublication.publishedAt)
		assertEquals(publishedForm.publishedBy, formAfterPublication.publishedBy)
	}

	@Test
	fun testGetPublishedFormTranslationsReturnsPublishedLanguagesByDefault() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val translationKey = "Sannheten"
		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(key = translationKey, nb = "Sannheten", nn = "Sanninga", en = "The truth"),
			authToken
		).assertSuccess()

		testFormsApi.publishForm(formPath, formRevision, authToken, listOf(LanguageCode.NB, LanguageCode.NN))
			.assertSuccess()
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "nn"), it.translations?.keys)
			}
	}

	@Test
	fun testBadRequestWhenDuplicateLanguageInPublishFormRequest() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val translationKey = "Sannheten"
		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(key = translationKey, nb = "Sannheten", nn = "Sanninga", en = "The truth"),
			authToken
		).assertSuccess()

		testFormsApi.publishForm(formPath, formRevision, authToken, listOf(LanguageCode.NB, LanguageCode.NB))
			.assertHttpStatus(HttpStatus.BAD_REQUEST)

		testFormsApi.getPublishedForm(formPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

	@Test
	fun testGetPublishedFormTranslationsReturnsOnlyRequestedLanguages() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val translationKey = "Sannheten"
		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(key = translationKey, nb = "Sannheten", nn = "Sanninga", en = "The truth"),
			authToken
		).assertSuccess()

		// Publish all languages
		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.getPublishedFormTranslations(formPath, listOf(LanguageCode.NB, LanguageCode.NN))
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "nn"), it.translations?.keys)
			}
		testFormsApi.getPublishedFormTranslations(formPath, listOf(LanguageCode.EN))
			.assertSuccess()
			.body.let {
				assertEquals(setOf("en"), it.translations?.keys)
			}
		testFormsApi.getPublishedFormTranslations(formPath, listOf(LanguageCode.NN))
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nn"), it.translations?.keys)
			}
	}

	@Test
	fun testPublishFormAndTranslationsWithDifferentLanguagesEnabled() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val translationKey1 = "Tester"
		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(key = translationKey1, nb = "Tester", nn = "Testar", en = "Testing"),
			authToken
		).assertSuccess()

		val translationKey2 = "Sykdom"
		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(key = translationKey2, nb = "Sykdom", nn = "Sjukdom", en = "Illness"),
			authToken
		).assertSuccess()

		// Default: only 'nb' is published
		testFormsApi.publishForm(formPath, formRevision, authToken)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb"), it.publishedLanguages)
			}
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb"), it.translations?.keys)
				assertEquals("Tester", it.translations?.get("nb")?.get(translationKey1))
				assertEquals("Sykdom", it.translations?.get("nb")?.get(translationKey2))
			}
		testFormsApi.getForm(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb"), it.publishedLanguages)
			}

		// Publish "nb" and "nn"
		testFormsApi.publishForm(formPath, formRevision, authToken, listOf(LanguageCode.NB, LanguageCode.NN))
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "nn"), it.publishedLanguages)
			}
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "nn"), it.translations?.keys)
				assertEquals("Tester", it.translations?.get("nb")?.get(translationKey1))
				assertEquals("Sykdom", it.translations?.get("nb")?.get(translationKey2))
				assertEquals("Testar", it.translations?.get("nn")?.get(translationKey1))
				assertEquals("Sjukdom", it.translations?.get("nn")?.get(translationKey2))
			}
		testFormsApi.getForm(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "nn"), it.publishedLanguages)
			}

		// Publish only form, keep previously published form translations
		testFormsApi.publishForm(formPath, formRevision, authToken, skipTranslations = true)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "nn"), it.publishedLanguages)
			}
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "nn"), it.translations?.keys)
				assertEquals("Tester", it.translations?.get("nb")?.get(translationKey1))
				assertEquals("Sykdom", it.translations?.get("nb")?.get(translationKey2))
				assertEquals("Testar", it.translations?.get("nn")?.get(translationKey1))
				assertEquals("Sjukdom", it.translations?.get("nn")?.get(translationKey2))
			}
		testFormsApi.getForm(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "nn"), it.publishedLanguages)
			}

		// Publish "nb" and "en"
		testFormsApi.publishForm(formPath, formRevision, authToken, listOf(LanguageCode.NB, LanguageCode.EN))
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "en"), it.publishedLanguages)
			}
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "en"), it.translations?.keys)
				assertEquals("Tester", it.translations?.get("nb")?.get(translationKey1))
				assertEquals("Sykdom", it.translations?.get("nb")?.get(translationKey2))
				assertEquals("Testing", it.translations?.get("en")?.get(translationKey1))
				assertEquals("Illness", it.translations?.get("en")?.get(translationKey2))
			}
		testFormsApi.getForm(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "en"), it.publishedLanguages)
			}

		// Publish "nb", "en" and "nn"
		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()
			.body
		testFormsApi.getPublishedFormTranslations(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(setOf("nb", "en", "nn"), it.translations?.keys)
				assertEquals("Tester", it.translations?.get("nb")?.get(translationKey1))
				assertEquals("Sykdom", it.translations?.get("nb")?.get(translationKey2))
				assertEquals("Testar", it.translations?.get("nn")?.get(translationKey1))
				assertEquals("Sjukdom", it.translations?.get("nn")?.get(translationKey2))
				assertEquals("Testing", it.translations?.get("en")?.get(translationKey1))
				assertEquals("Illness", it.translations?.get("en")?.get(translationKey2))
			}
		testFormsApi.getForm(formPath)
			.assertSuccess()
			.body.let {
				assertEquals(listOf("nb", "nn", "en"), it.publishedLanguages)
			}
	}

	@Test
	fun testPublishFormFailsWhenSkippingTranslationOnFirstPublish() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val publishError = testFormsApi.publishForm(formPath, formRevision, authToken, skipTranslations = true)
			.assertClientError()
			.errorBody

		assertEquals(
			"Found no existing publication of this form, languages must therefore be provided on publish",
			publishError.errorMessage
		)
	}

	@Test
	fun testPublishFormFailureWhenWrongRevision() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body

		val formPath = newForm.path!!
		val wrongRevision = newForm.revision!! + 1
		val errorBody = testFormsApi.publishForm(formPath, wrongRevision, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
			.errorBody

		assertEquals("Conflict", errorBody.errorMessage)
	}

	@Test
	fun testEditingFormAfterPublication() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest(title = "Original title")
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		assertEquals("Original title", newForm.title)
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!
		val originalTitle = newForm.title!!

		testFormsApi.publishForm(formPath, formRevision, authToken)
			.assertSuccess()

		val updateRequest = FormsTestdata.updateFormRequest(
			title = "Updated title",
			components = newForm.components!!,
			properties = newForm.properties!!
		)
		val updatedForm = testFormsApi.updateForm(formPath, formRevision, updateRequest, authToken)
			.assertSuccess()
			.body
		assertEquals("Updated title", updatedForm.title)

		val publishedForm = testFormsApi.getPublishedForm(formPath)
			.assertSuccess()
			.body
		assertEquals(originalTitle, publishedForm.title)
	}

	@Test
	fun testGetPublishedFormWhenNotPublished() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!

		val errorBody = testFormsApi.getPublishedForm(formPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody

		assertEquals("Form not published", errorBody.errorMessage)
	}

	@Test
	fun testPublishFormWhenDeleted() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!

		testFormsApi.deleteForm(formPath, newForm.revision!!, authToken)
			.assertSuccess()

		val errorBody = testFormsApi.publishForm(formPath, newForm.revision!!, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody

		assertEquals("Form not found", errorBody.errorMessage)
	}

	@Test
	fun testGetPublishedFormWhenDeleted() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!

		testFormsApi.deleteForm(formPath, newForm.revision!!, authToken)
			.assertSuccess()

		val errorBody = testFormsApi.getPublishedForm(formPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody

		assertEquals("Form not found", errorBody.errorMessage)
	}

	@Test
	fun testGetPublishedFormWithInvalidPath() {
		val invalidFormPath = "invalidpath"

		val errorBody = testFormsApi.getPublishedForm(invalidFormPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody

		assertEquals("Form not found", errorBody.errorMessage)
	}

	@Test
	fun testPublishFormWithInvalidPath() {
		val authToken = mockOAuth2Server.createMockToken()
		val invalidFormPath = "invalidpath"
		val formRevision = 1

		val errorBody = testFormsApi.publishForm(invalidFormPath, formRevision, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
			.errorBody

		assertEquals("Form not found", errorBody.errorMessage)
	}

	@Test
	fun testPublishFormWithoutAuthentication() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val publishAuthToken = null
		val errorBody = testFormsApi.publishForm(formPath, formRevision, publishAuthToken)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
			.errorBody

		assertEquals("Unauthorized", errorBody.errorMessage)
	}

	@Test
	fun testPublishFormWithUserWithoutCorrectAuthorization() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = FormsTestdata.newFormRequest()
		val newForm = testFormsApi.createForm(createRequest, authToken)
			.assertSuccess()
			.body
		val formPath = newForm.path!!
		val formRevision = newForm.revision!!

		val invalidPublishAuthToken = mockOAuth2Server.createMockToken(groups = emptyList())
		val errorBody = testFormsApi.publishForm(formPath, formRevision, invalidPublishAuthToken)
			.assertHttpStatus(HttpStatus.FORBIDDEN)
			.errorBody

		assertEquals("Forbidden", errorBody.errorMessage)
	}

	@Test
	fun testGetPublishedFormsWhenNoFormsHaveBeenPublished() {
		val publishedForms = testFormsApi.getPublishedForms()
			.assertSuccess()
			.body

		assertEquals(0, publishedForms.size)
	}

	@Test
	fun testGetPublishedForms() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest1 = FormsTestdata.newFormRequest(skjemanummer = "NAV 24-12.03")
		val newForm1 = testFormsApi.createForm(createRequest1, authToken)
			.assertSuccess()
			.body
		val formPath1 = newForm1.path!!
		val formRevision1 = newForm1.revision!!
		testFormsApi.publishForm(formPath1, formRevision1, authToken)
			.assertSuccess()

		val createRequest2 = FormsTestdata.newFormRequest(skjemanummer = "NAV 12-45.04B")
		val newForm2 = testFormsApi.createForm(createRequest2, authToken)
			.assertSuccess()
			.body
		val formPath2 = newForm2.path!!
		val formRevision2 = newForm2.revision!!
		testFormsApi.publishForm(formPath2, formRevision2, authToken)
			.assertSuccess()

		val createRequest3 = FormsTestdata.newFormRequest(skjemanummer = "NAV 11-40.02")
		val newForm3 = testFormsApi.createForm(createRequest3, authToken)
			.assertSuccess()
			.body

		val publishedForms = testFormsApi.getPublishedForms()
			.assertSuccess()
			.body

		assertEquals(2, publishedForms.size)
		assertTrue(publishedForms.any { it.path == formPath1 })
		assertTrue(publishedForms.any { it.path == formPath2 })
		assertFalse(publishedForms.any { it.path == newForm3.path!! })
	}

	@Test
	fun testFetchingFormAfterPublishAndUpdate() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body

		val formPath = form.path!!
		testFormsApi.publishForm(formPath, form.revision!!, authToken, LanguageCode.entries)
			.assertSuccess()

		val titleUpdated = "Oppdatert skjematittel"
		testFormsApi.updateForm(formPath, 1, FormsTestdata.updateFormRequest(titleUpdated), authToken)
			.assertSuccess().body.run {
				assertEquals(titleUpdated, this.title)
				assertNotNull(this.publishedAt)
				assertEquals(FormStatus.pending, this.status)
			}

		testFormsApi.getForm(formPath).assertSuccess().body.run {
			assertEquals(titleUpdated, this.title)
			assertNotNull(this.publishedAt)
			assertEquals(FormStatus.pending, this.status)
		}

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(titleUpdated, correctForm.title)
			assertNotNull(correctForm.publishedAt)
			assertEquals(FormStatus.pending, correctForm.status)
		}
	}

	@Test
	fun testGetAllFormsWhilePublishing() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(title = "Initiell tittel"), authToken)
			.assertSuccess().body
		val formPath = form.path!!

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(1, correctForm.revision)
			assertEquals("Initiell tittel", correctForm.title)
			assertNull(correctForm.publishedAt)
			assertEquals(FormStatus.draft, correctForm.status)
		}

		testFormsApi.updateForm(formPath, 1, FormsTestdata.updateFormRequest("Oppdatert tittel"), authToken)
			.assertSuccess()

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(2, correctForm.revision)
			assertEquals("Oppdatert tittel", correctForm.title)
			assertNull(correctForm.publishedAt)
			assertEquals(FormStatus.draft, correctForm.status)
		}

		testFormsApi.publishForm(formPath, 2, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(2, correctForm.revision)
			assertNotNull(correctForm.publishedAt)
			assertEquals(FormStatus.published, correctForm.status)
		}

		val finalTitle = "Endelig tittel"
		val updateFormResponse =
			testFormsApi.updateForm(formPath, 2, FormsTestdata.updateFormRequest(finalTitle), authToken)
				.assertSuccess().body.run {
					assertNotNull(this.publishedAt)
					assertEquals(finalTitle, this.title)
					this
				}

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(3, correctForm.revision)
			assertEquals(finalTitle, correctForm.title)
			assertNotNull(correctForm.publishedAt)
			assertEquals(updateFormResponse.publishedAt, correctForm.publishedAt)
			assertEquals(FormStatus.pending, correctForm.status)
		}

	}

	@Test
	fun testUnpublishForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!
		val formRevision = form.revision!!

		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.getPublishedForms().assertSuccess().body.run {
			assertEquals(1, this.count { it.path == formPath })
		}

		testFormsApi.getPublishedForm(formPath)
			.assertSuccess().body.run {
				assertNotNull(this.publishedAt)
				assertNotNull(this.publishedBy)
			}

		testFormsApi.unpublishForm(formPath, authToken)
			.assertSuccess()

		testFormsApi.getPublishedForm(formPath)
			.assertHttpStatus(HttpStatus.NOT_FOUND)

		testFormsApi.getForms().assertSuccess().body.run {
			val correctForm = this.first { it.path == formPath }
			assertEquals(1, correctForm.revision)
			assertEquals(FormStatus.unpublished, correctForm.status)
			assertNotNull(correctForm.publishedAt)
			assertNotNull(correctForm.publishedBy)
		}

		testFormsApi.getPublishedForms().assertSuccess().body.run {
			assertTrue(this.none { it.path == formPath })
		}

		testFormsApi.getForm(formPath).assertSuccess().body.run {
			assertEquals(1, this.revision)
			assertNotNull(this.publishedAt)
			assertNotNull(this.publishedBy)
			assertEquals(FormStatus.unpublished, this.status)
		}
	}

	@Test
	fun testUnpublishFormWhichNeverHaveBeenPublished() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!

		testFormsApi.unpublishForm(formPath, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

	@Test
	fun testUnpublishNonexistentForm() {
		val authToken = mockOAuth2Server.createMockToken()
		testFormsApi.unpublishForm("doesnotexist", authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

	@Test
	fun testUnpublishFormWithoutAuthToken() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!
		val formRevision = form.revision!!

		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.unpublishForm(formPath, authToken = null)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)

		testFormsApi.getPublishedForm(formPath)
			.assertSuccess().body.run {
				assertEquals(FormStatus.published, this.status)
				assertNotNull(this.publishedAt)
				assertNotNull(this.publishedBy)
			}
	}

	@Test
	fun testUnpublishAlreadyUnpublishedForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!
		val formRevision = form.revision!!

		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.unpublishForm(formPath, authToken)
			.assertSuccess()

		testFormsApi.getPublishedForm(formPath).assertHttpStatus(HttpStatus.NOT_FOUND)
		val unpublishedForm = testFormsApi.getForm(formPath)
			.assertSuccess().body.run {
				assertEquals(FormStatus.unpublished, this.status)
				this
			}

		// form is already unpublished, so expecting BAD_REQUEST
		testFormsApi.unpublishForm(formPath, authToken)
			.assertHttpStatus(HttpStatus.BAD_REQUEST)

		testFormsApi.getPublishedForm(formPath).assertHttpStatus(HttpStatus.NOT_FOUND)
		testFormsApi.getForm(formPath)
			.assertSuccess().body.run {
				assertEquals(FormStatus.unpublished, this.status)
				assertEquals(unpublishedForm.publishedAt, this.publishedAt)
				assertEquals(unpublishedForm.publishedBy, this.publishedBy)
				this
			}
	}

	@Test
	fun testPublishFormAfterUnpublish() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(FormsTestdata.newFormRequest(), authToken)
			.assertSuccess().body
		val formPath = form.path!!
		val formRevision = form.revision!!

		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.getPublishedForm(formPath)
			.assertSuccess().body.run {
				assertEquals(FormStatus.published, this.status)
			}

		testFormsApi.unpublishForm(formPath, authToken)
			.assertSuccess()

		testFormsApi.getPublishedForm(formPath).assertHttpStatus(HttpStatus.NOT_FOUND)

		// publish the unpublished form
		testFormsApi.publishForm(formPath, formRevision, authToken, LanguageCode.entries)
			.assertSuccess()

		testFormsApi.getPublishedForm(formPath)
			.assertSuccess().body.run {
				assertEquals(FormStatus.published, this.status)
			}
	}

}
