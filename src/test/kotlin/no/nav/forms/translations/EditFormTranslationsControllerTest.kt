package no.nav.forms.translations

import no.nav.forms.ApplicationTest
import no.nav.forms.exceptions.db.DbError
import no.nav.forms.model.*
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.Skjemanummer
import no.nav.forms.utils.toFormPath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class EditFormTranslationsControllerTest : ApplicationTest(setupPublishedGlobalTranslations = true) {

	val skjemanummer: Skjemanummer = "NAV 12-34.56"
	val formPath = skjemanummer.toFormPath()

	@BeforeEach
	fun createForm() {
		val authToken = mockOAuth2Server.createMockToken()

		// Create form with the given skjemanummer
		val newFormRequest = FormsTestdata.newFormRequest(skjemanummer = skjemanummer)
		testFormsApi.createForm(newFormRequest, authToken)
			.assertSuccess()
	}

	@Test
	fun testChangeOfFormTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(
			key = "Opplysninger om søker",
			nb = "Opplysninger om søker",
			nn = "Opppplysningar om søkjaren",
		)
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		assertEquals(createRequest.key, createResponse.body.key)
		assertEquals(1, createResponse.body.revision)
		assertEquals(createRequest.nb, createResponse.body.nb)
		assertEquals(createRequest.nn, createResponse.body.nn)
		assertNull(createResponse.body.en)

		val updateRequest = UpdateFormTranslationRequest(
			nb = createResponse.body.nb,
			nn = "Opplysningar om søkjaren",
			en = "Information about the applicant",
		)
		val updateResponse = testFormsApi.updateFormTranslation(
			formPath,
			createResponse.body.id,
			createResponse.body.revision!!,
			updateRequest,
			authToken,
		).assertSuccess()

		assertEquals(createRequest.key, updateResponse.body.key)
		assertEquals(2, updateResponse.body.revision)
		assertEquals(updateRequest.nb, updateResponse.body.nb)
		assertEquals(updateRequest.nn, updateResponse.body.nn)
		assertEquals(updateRequest.en, updateResponse.body.en)
	}

	@Test
	fun failsOnCreateDuplicateFormTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(
			key = "Opplysninger om søker",
			nb = "Opplysninger om søker",
			nn = "Opppplysningar om søkjaren",
		)
		testFormsApi.createFormTranslation(formPath, createRequest, authToken)
			.assertSuccess()

		testFormsApi.createFormTranslation(formPath, createRequest, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
	}

	@Test
	fun failsOnCreateWhenFormIsDeleted() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(
			key = "Tester",
			nb = "Tester",
		)
		testFormsApi.deleteForm(formPath, 1, authToken).assertSuccess()

		testFormsApi.createFormTranslation(formPath, createRequest, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

	@Test
	fun failsOnEditAlreadyEditedFormTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(
			key = "Tester",
			nb = "Tester",
		)
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()
		val formTranslationId = createResponse.body.id

		assertEquals(1, createResponse.body.revision)

		val updateRequest = UpdateFormTranslationRequest(
			nb = createResponse.body.nb,
			nn = "Testar",
		)
		testFormsApi.updateFormTranslation(
			formPath,
			formTranslationId,
			1,
			updateRequest,
			authToken,
		).assertSuccess()

		testFormsApi.updateFormTranslation(
			formPath,
			formTranslationId,
			1,
			updateRequest,
			authToken,
		).assertHttpStatus(HttpStatus.CONFLICT)
	}

	@Test
	fun failsDueToFormPathMismatchOnUpdate() {
		val authToken = mockOAuth2Server.createMockToken()

		val newFormRequest1 = FormsTestdata.newFormRequest(skjemanummer = "NAV 11-11.11")
		testFormsApi.createForm(newFormRequest1, authToken)
			.assertSuccess()
		val newFormRequest2 = FormsTestdata.newFormRequest(skjemanummer = "NAV 22-22.22")
		testFormsApi.createForm(newFormRequest2, authToken)
			.assertSuccess()

		val nav111111 = newFormRequest1.skjemanummer.toFormPath()
		val nav222222 = newFormRequest2.skjemanummer.toFormPath()

		val createRequest = NewFormTranslationRequestDto(
			key = "Tester",
			nb = "Tester",
		)
		val createResponse1 = testFormsApi.createFormTranslation(nav111111, createRequest, authToken).assertSuccess()

		val createResponse2 = testFormsApi.createFormTranslation(nav222222, createRequest, authToken).assertSuccess()

		val updateRequest = UpdateFormTranslationRequest(
			nb = createResponse1.body.nb,
			nn = "Testar",
		)
		val updateResponse1 = testFormsApi.updateFormTranslation(
			nav111111,
			createResponse2.body.id,
			1,
			updateRequest,
			authToken,
		)
		assertEquals(HttpStatus.BAD_REQUEST.value(), updateResponse1.statusCode.value())
	}

	@Test
	fun testGetAllFormTranslations() {
		val authToken = mockOAuth2Server.createMockToken()

		val createResponse1 = testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Foo",
				nb = "Foo",
			),
			authToken
		).assertSuccess()

		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Bar",
				nb = "Bar",
			),
			authToken
		).assertSuccess()

		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Foobar",
				nb = "Foobar",
			),
			authToken
		).assertSuccess()

		testFormsApi.updateFormTranslation(
			formPath,
			createResponse1.body.id,
			createResponse1.body.revision!!,
			UpdateFormTranslationRequest(
				nb = createResponse1.body.nb,
				nn = "Nynorskfoo"
			),
			authToken
		).assertSuccess()

		val response = testFormsApi.getFormTranslations(formPath).assertSuccess()
		assertEquals(3, response.body.size)
	}

	@Test
	fun testCreateFormTranslationWithGlobalValue() {
		val authToken = mockOAuth2Server.createMockToken()

		val globalTranslationResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest("Ja", "skjematekster", "Ja", "Ja", "Yes"),
			authToken
		).assertSuccess()
		assertNotNull(globalTranslationResponse.body.id)

		testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Ja",
				globalTranslationId = globalTranslationResponse.body.id,
			),
			authToken
		).assertSuccess()

		val response = testFormsApi.getFormTranslations(formPath).assertSuccess()
		assertEquals(1, response.body.size)
		val formTranslation = response.body.firstOrNull() as FormTranslationDto
		assertEquals(globalTranslationResponse.body.id, formTranslation.globalTranslationId)
		assertEquals(globalTranslationResponse.body.nb, formTranslation.nb)
		assertEquals(globalTranslationResponse.body.nn, formTranslation.nn)
		assertEquals(globalTranslationResponse.body.en, formTranslation.en)
	}

	@Test
	fun failsOnCreateWhenGlobalTranslationDoesNotExist() {
		val authToken = mockOAuth2Server.createMockToken()

		val createResponse = testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Ja",
				globalTranslationId = 37,
			),
			authToken
		).assertHttpStatus(HttpStatus.BAD_REQUEST)

		assertEquals("Global translation not found", createResponse.errorBody.errorMessage)
	}

	@Test
	fun failsOnCreateWhenBothGlobalTranslationIdAndTranslationExistInRequest() {
		val authToken = mockOAuth2Server.createMockToken()

		val globalTranslationResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest("Ja", "skjematekster", "Ja", "Ja", "Yes"),
			authToken
		).assertSuccess()
		assertNotNull(globalTranslationResponse.body.id)

		val createResponse = testFormsApi.createFormTranslation(
			formPath,
			NewFormTranslationRequestDto(
				key = "Ja",
				globalTranslationId = globalTranslationResponse.body.id,
				nb = "Ja",
				nn = "Ja",
				en = "Yes",
			),
			authToken
		).assertHttpStatus(DbError.FORMSAPI_001.httpStatus)
		assertEquals(
			DbError.FORMSAPI_001.message,
			createResponse.errorBody.errorMessage
		)
	}

	@Test
	fun failsOnUpdateWhenBothGlobalTranslationAndLocalTranslationsAreProvided() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(
			key = "Ja",
			nb = "Ja",
			nn = "Ja",
		)
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		val globalTranslationResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest("Ja", "skjematekster", "Ja", "Ja", "Yes"),
			authToken
		).assertSuccess()
		assertNotNull(globalTranslationResponse.body.id)

		val updateRequest = UpdateFormTranslationRequest(
			globalTranslationId = globalTranslationResponse.body.id,
			nb = createResponse.body.nb,
			nn = "Jauda",
			en = "Yes",
		)
		val updateResponse = testFormsApi.updateFormTranslation(
			formPath,
			createResponse.body.id,
			createResponse.body.revision!!,
			updateRequest,
			authToken,
		).assertHttpStatus(DbError.FORMSAPI_001.httpStatus)
		assertEquals(
			DbError.FORMSAPI_001.message,
			updateResponse.errorBody.errorMessage
		)
	}

	@Test
	fun testUpdateFormTranslationAndLinkToGlobalTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(key = "Nei", nb = "Nei", nn = "Nei")
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		val globalTranslationResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(key = "Ja", tag = "skjematekster", nb = "Ja", nn = "Ja", en = "Yes"),
			authToken
		).assertSuccess()
		assertNotNull(globalTranslationResponse.body.id)

		val updateRequest = UpdateFormTranslationRequest(
			globalTranslationId = globalTranslationResponse.body.id,
		)
		val updateResponse = testFormsApi.updateFormTranslation(
			formPath,
			createResponse.body.id,
			createResponse.body.revision!!,
			updateRequest,
			authToken,
		).assertSuccess()

		assertEquals(globalTranslationResponse.body.nb, updateResponse.body.nb)
		assertEquals(globalTranslationResponse.body.nn, updateResponse.body.nn)
		assertEquals(globalTranslationResponse.body.en, updateResponse.body.en)
	}

	@Test
	fun testDeleteFormTranslationWithoutAuthToken() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(key = "Nei", nb = "Nei")
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		testFormsApi.deleteFormTranslation(formPath, createResponse.body.id)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
	}

	@Test
	fun testDeleteNonExistingFormTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		testFormsApi.deleteFormTranslation(formPath, 123L, authToken)
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

	@Test
	fun testDeleteFormTranslation() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(key = "Nei", nb = "Nei")
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		testFormsApi.deleteFormTranslation(formPath, createResponse.body.id, authToken).assertSuccess()

		val formTranslationsResponse = testFormsApi.getFormTranslations(formPath).assertSuccess()
		assertEquals(0, formTranslationsResponse.body.size)
	}

	@Test
	fun testThatDeleteFormTranslationDoesNotAffectPublishedFormTranslations() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest = NewFormTranslationRequestDto(key = "Nei", nb = "Nei")
		val createResponse = testFormsApi.createFormTranslation(formPath, createRequest, authToken).assertSuccess()

		testFormsApi.publishForm(formPath, 1, authToken, LanguageCode.entries).assertSuccess()

		val currentFormTranslationsBeforeDelete = testFormsApi.getFormTranslations(formPath).assertSuccess().body
		assertEquals(1, currentFormTranslationsBeforeDelete.size)

		testFormsApi.deleteFormTranslation(formPath, createResponse.body.id, authToken).assertSuccess()

		val currentFormTranslationsAfterDelete = testFormsApi.getFormTranslations(formPath).assertSuccess().body
		assertEquals(0, currentFormTranslationsAfterDelete.size)

		val formTranslationPublication = testFormsApi.getPublishedFormTranslations(formPath, LanguageCode.entries).assertSuccess().body
		assertNotNull(formTranslationPublication.publishedAt)
		assertNotNull(formTranslationPublication.publishedBy)

		val publishedTranslationsMap = formTranslationPublication.translations as Map<String, Map<String, String>>
		assertEquals(setOf("nb","nn","en"), publishedTranslationsMap.keys)

		val bokmal = publishedTranslationsMap["nb"]
		assertEquals("Nei", bokmal?.get("Nei"))
	}

	@Test
	fun testRecreateFormTranslationAfterDelete() {
		val authToken = mockOAuth2Server.createMockToken()

		val createRequest1 = NewFormTranslationRequestDto(key = "Nei", nb = "Nei")
		val createResponse1 = testFormsApi.createFormTranslation(formPath, createRequest1, authToken).assertSuccess()

		testFormsApi.deleteFormTranslation(formPath, createResponse1.body.id, authToken).assertSuccess()

		val createRequest2 = NewFormTranslationRequestDto(key = "Nei", nb = "Nei", en = "No")
		testFormsApi.createFormTranslation(formPath, createRequest2, authToken).assertSuccess()

		val formTranslationsResponse = testFormsApi.getFormTranslations(formPath).assertSuccess()
		assertEquals(1, formTranslationsResponse.body.size)
		val formTranslation = formTranslationsResponse.body[0]
		assertEquals(2, formTranslation.revision)
		assertEquals(createRequest2.key, formTranslation.key)
		assertEquals(createRequest2.nb, formTranslation.nb)
		assertEquals(createRequest2.nn, formTranslation.nn)
		assertEquals(createRequest2.en, formTranslation.en)
	}

	@Test
	fun testThatModificationOfFormTranslationAfterTheFormRevisionHasBeenPublishedDoesNotAffectPublishedTranslations() {
		val authToken = mockOAuth2Server.createMockToken()

		val translationKey = "Ja"
		val createRequest = NewFormTranslationRequestDto(key = translationKey, nb = "Ja", nn = "Jau", en = "Yes")
		val formTranslation = testFormsApi.createFormTranslation(formPath, createRequest, authToken)
			.assertSuccess().body

		testFormsApi.publishForm(formPath, 1, authToken, LanguageCode.entries).assertSuccess()

		val updateRequest = UpdateFormTranslationRequest(
			nb = "Ja",
			en = "Yeah",
		)
		testFormsApi.updateFormTranslation(
			formPath,
			formTranslation.id,
			formTranslation.revision!!,
			updateRequest,
			authToken,
		).assertSuccess()

		val currentFormTranslations = testFormsApi.getFormTranslations(formPath).assertSuccess().body
		assertEquals(1, currentFormTranslations.size)
		val updatedFormTranslation = currentFormTranslations.first()
		assertEquals(2, updatedFormTranslation.revision)
		assertEquals(updateRequest.nb, updatedFormTranslation.nb)
		assertEquals(updateRequest.en, updatedFormTranslation.en)
		assertNull(updatedFormTranslation.nn)

		val publication = testFormsApi.getPublishedFormTranslations(formPath, LanguageCode.entries).assertSuccess().body
		assertNotNull(publication.publishedAt)
		assertNotNull(publication.publishedBy)

		val publishedTranslations = publication.translations as Map<String, Map<String, String>>
		assertEquals(setOf("nb","nn","en"), publishedTranslations.keys)

		val bokmalMap = publishedTranslations["nb"]
		assertEquals("Ja", bokmalMap?.get(translationKey))

		val nynorskMap = publishedTranslations["nn"]
		assertEquals("Jau", nynorskMap?.get(translationKey))

		val englishMap = publishedTranslations["en"]
		assertEquals("Yes", englishMap?.get(translationKey))
	}

}
