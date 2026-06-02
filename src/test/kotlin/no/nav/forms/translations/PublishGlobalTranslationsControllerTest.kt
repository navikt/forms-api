package no.nav.forms.translations

import no.nav.forms.ApplicationTest
import no.nav.forms.model.FormStatus
import no.nav.forms.model.NewFormTranslationRequestDto
import no.nav.forms.model.UpdateGlobalTranslationRequest
import no.nav.forms.testutils.MOCK_USER_GROUP_ID
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import no.nav.forms.utils.LanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublishGlobalTranslationsControllerTest : ApplicationTest(setupPublishedGlobalTranslations = true) {

	@Test
	fun testPublishInformationWithoutTranslations() {
		val response = testFormsApi.getGlobalTranslationPublication().assertSuccess()

		assertNotNull(response.body.publishedAt)
		assertNotNull(response.body.publishedBy)
		assertNull(response.body.translations)
	}

	@Test
	fun testPublishInformation() {
		val response = testFormsApi.getGlobalTranslationPublication(listOf("en", "nn")).assertSuccess()

		assertNotNull(response.body.publishedAt)
		assertNotNull(response.body.publishedBy)
		assertNotNull(response.body.translations)

		val translationsInResponse: Map<String, Map<String, String>> = response.body.translations as Map<String, Map<String, String>>
		assertEquals(setOf("en","nn"), translationsInResponse.keys)
		assertNotNull(translationsInResponse["en"])
		assertNotNull(translationsInResponse["nn"])
		assertNull(translationsInResponse["nb"])

		val translations = getPublishedGlobalTranslations()
		val tFornavn = translations.values.find { it.key == "Fornavn" }!!
		val tRequired = translations.values.find { it.key == "required" }!!

		val nynorsk = translationsInResponse["nn"]
		assertEquals(
			mapOf(
				tRequired.key to tRequired.nn,
				tFornavn.key to tFornavn.nn
			),
			nynorsk
		)

		val english = translationsInResponse["en"]
		assertEquals(
			mapOf(
				tRequired.key to tRequired.en,
				tFornavn.key to tFornavn.en
			),
			english
		)
	}

	@Test
	fun testPublish() {
		val translations = getPublishedGlobalTranslations()
		val tFornavn = translations.values.find { it.key == "Fornavn" }!!
		val tRequired = translations.values.find { it.key == "required" }!!

		val publishedGlobalTranslationsBokmal = testFormsApi.getPublishedGlobalTranslations(LanguageCode.NB.value)
		val bokmal = publishedGlobalTranslationsBokmal.body
		assertEquals(1, bokmal.keys.size)
		assertEquals(
			mapOf(
				tRequired.key to tRequired.nb
			),
			bokmal
		)
		assertTrue(bokmal.keys.none { it == tFornavn.key })
		bokmal.keys.find { it == tRequired.key }.also { assertEquals(tRequired.nb, bokmal[it])}

		val publishedGlobalTranslationsNynorsk = testFormsApi.getPublishedGlobalTranslations(LanguageCode.NN.value)
		val nynorsk = publishedGlobalTranslationsNynorsk.body
		assertEquals(
			mapOf(
				tRequired.key to tRequired.nn,
				tFornavn.key to tFornavn.nn
			),
			nynorsk
		)

		val publishedGlobalTranslationsEnglish = testFormsApi.getPublishedGlobalTranslations(LanguageCode.EN.value)
		val english = publishedGlobalTranslationsEnglish.body
		assertEquals(
			mapOf(
				tRequired.key to tRequired.en,
				tFornavn.key to tFornavn.en
			),
			english
		)
	}

	@Test
	fun testChangeAndPublish() {
		val authToken = mockOAuth2Server.createMockToken()
		val globalTranslations = testFormsApi.getGlobalTranslations().body
		val tFornavn = globalTranslations.find { it.key == "Fornavn" }!!
		val tRequired = getPublishedGlobalTranslations().values.find { it.key == "required" }!!

		val updatedEnTranslation = "${tFornavn.en}postfix"
		testFormsApi.putGlobalTranslation(
			tFornavn.id,
			tFornavn.revision!!,
			UpdateGlobalTranslationRequest(
				nb = tFornavn.nb,
				en = updatedEnTranslation,
				nn = tFornavn.nn,
			),
			authToken,
		).assertSuccess()

		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()

		val publishedGlobalTranslationsEnglish = testFormsApi.getPublishedGlobalTranslations(LanguageCode.EN.value)
		val english = publishedGlobalTranslationsEnglish.body
		assertEquals(2, english.keys.size)
		assertEquals(
			mapOf(
				tRequired.key to tRequired.en,
				tFornavn.key to updatedEnTranslation
			),
			english
		)
	}

	@Test
	fun testPublishWhenNotAuthenticated() {
		val authToken = null
		testFormsApi.publishGlobalTranslations(authToken).assertClientError()
	}

	@Test
	fun testPublishWhenNotAdmin() {
		val authToken = mockOAuth2Server.createMockToken(groups = listOf(MOCK_USER_GROUP_ID))
		testFormsApi.publishGlobalTranslations(authToken).assertClientError()
	}

	@Test
	fun testPublishGlobalTranslationsCreatesNewPublicationForPublishedForm() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = createAndPublishForm(
			authToken = authToken,
			skjemanummer = "NAV 11-11.11",
			title = "Publisert skjema",
			withTranslation = true,
		)

		val publishedFormBefore = testFormsApi.getPublishedForm(formPath).assertSuccess().body
		val publishedTranslationsBefore = testFormsApi.getPublishedFormTranslations(formPath).assertSuccess().body.translations
		val publishedGlobalTranslationPublicationBefore = testFormsApi
			.getGlobalTranslationPublication(listOf(LanguageCode.EN.value))
			.assertSuccess()
			.body

		val updatedEnTranslation = changeAndPublishGlobalTranslations(authToken)

		val publishedFormAfter = testFormsApi.getPublishedForm(formPath).assertSuccess().body
		val publishedTranslationsAfter = testFormsApi.getPublishedFormTranslations(formPath).assertSuccess().body.translations
		val publishedGlobalTranslationPublicationAfter = testFormsApi
			.getGlobalTranslationPublication(listOf(LanguageCode.EN.value))
			.assertSuccess()
			.body
		val publishedFormsEntry = testFormsApi.getPublishedForms().assertSuccess().body.first { it.path == formPath }
		val englishGlobalTranslations = testFormsApi.getPublishedGlobalTranslations(LanguageCode.EN.value).body

		assertNotEquals(publishedGlobalTranslationPublicationBefore.publishedAt, publishedGlobalTranslationPublicationAfter.publishedAt)
		assertEquals(publishedFormBefore.revision, publishedFormAfter.revision)
		assertEquals(FormStatus.published, publishedFormAfter.status)
		assertEquals(publishedFormBefore.publishedLanguages, publishedFormAfter.publishedLanguages)
		assertEquals(publishedFormBefore.publishedAt, publishedFormAfter.publishedAt)
		assertNotEquals(publishedFormBefore.publicationId, publishedFormAfter.publicationId)
		assertEquals(publishedFormAfter.publicationId, publishedFormsEntry.publicationId)
		assertEquals(publishedFormAfter.publishedAt, publishedFormsEntry.publishedAt)
		assertEquals(publishedTranslationsBefore, publishedTranslationsAfter)
		assertEquals(updatedEnTranslation, englishGlobalTranslations["Fornavn"])
	}

	@Test
	fun testPublishGlobalTranslationsSkipsUnpublishedForms() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = createAndPublishForm(
			authToken = authToken,
			skjemanummer = "NAV 22-22.22",
			title = "Skjema som avpubliseres",
		)

		testFormsApi.unpublishForm(formPath, authToken).assertSuccess()

		val unpublishedFormBefore = testFormsApi.getForm(formPath).assertSuccess().body

		changeAndPublishGlobalTranslations(authToken)

		testFormsApi.getPublishedForm(formPath).assertHttpStatus(HttpStatus.NOT_FOUND)
		testFormsApi.getForm(formPath).assertSuccess().body.let {
			assertEquals(FormStatus.unpublished, it.status)
			assertEquals(unpublishedFormBefore.publicationId, it.publicationId)
			assertEquals(unpublishedFormBefore.publishedAt, it.publishedAt)
			assertEquals(unpublishedFormBefore.publishedBy, it.publishedBy)
		}
		assertTrue(testFormsApi.getPublishedForms().assertSuccess().body.none { it.path == formPath })
	}

	@Test
	fun testPublishGlobalTranslationsSkipsNeverPublishedForms() {
		val authToken = mockOAuth2Server.createMockToken()
		val form = testFormsApi.createForm(
			FormsTestdata.newFormRequest(
				skjemanummer = "NAV 33-33.33",
				title = "Skjema uten publisering",
			),
			authToken,
		).assertSuccess().body
		val formPath = form.path!!

		changeAndPublishGlobalTranslations(authToken)

		testFormsApi.getForm(formPath).assertSuccess().body.let {
			assertNull(it.publicationId)
			assertEquals(FormStatus.draft, it.status)
		}
		testFormsApi.getPublishedForm(formPath).assertHttpStatus(HttpStatus.NOT_FOUND)
		assertTrue(testFormsApi.getPublishedForms().assertSuccess().body.none { it.path == formPath })
	}

	@Test
	fun testPublishGlobalTranslationsDoesNothingWhenNothingChanged() {
		val authToken = mockOAuth2Server.createMockToken()
		val formPath = createAndPublishForm(
			authToken = authToken,
			skjemanummer = "NAV 44-44.44",
			title = "Skjema uten globale endringer",
		)

		val publishedGlobalTranslationPublicationBefore = testFormsApi
			.getGlobalTranslationPublication(listOf(LanguageCode.EN.value, LanguageCode.NN.value))
			.assertSuccess()
			.body
		val publishedFormBefore = testFormsApi.getPublishedForm(formPath).assertSuccess().body
		val publishedFormTranslationsBefore = testFormsApi.getPublishedFormTranslations(formPath).assertSuccess().body

		testFormsApi.publishGlobalTranslations(authToken).assertHttpStatus(HttpStatus.NO_CONTENT)

		val publishedGlobalTranslationPublicationAfter = testFormsApi
			.getGlobalTranslationPublication(listOf(LanguageCode.EN.value, LanguageCode.NN.value))
			.assertSuccess()
			.body
		val publishedFormAfter = testFormsApi.getPublishedForm(formPath).assertSuccess().body
		val publishedFormTranslationsAfter = testFormsApi.getPublishedFormTranslations(formPath).assertSuccess().body

		assertEquals(publishedGlobalTranslationPublicationBefore.publishedAt, publishedGlobalTranslationPublicationAfter.publishedAt)
		assertEquals(publishedGlobalTranslationPublicationBefore.publishedBy, publishedGlobalTranslationPublicationAfter.publishedBy)
		assertEquals(publishedGlobalTranslationPublicationBefore.translations, publishedGlobalTranslationPublicationAfter.translations)
		assertEquals(publishedFormBefore.publicationId, publishedFormAfter.publicationId)
		assertEquals(publishedFormBefore.publishedAt, publishedFormAfter.publishedAt)
		assertEquals(publishedFormTranslationsBefore.translations, publishedFormTranslationsAfter.translations)
	}

	@Test
	fun testPublishGlobalTranslationsCreatesNewPublicationsForAllPublishedForms() {
		val authToken = mockOAuth2Server.createMockToken()
		val firstFormPath = createAndPublishForm(
			authToken = authToken,
			skjemanummer = "NAV 55-55.55",
			title = "Første publiserte skjema",
		)
		val secondFormPath = createAndPublishForm(
			authToken = authToken,
			skjemanummer = "NAV 66-66.66",
			title = "Andre publiserte skjema",
		)

		val firstPublicationIdBefore = testFormsApi.getPublishedForm(firstFormPath).assertSuccess().body.publicationId
		val secondPublicationIdBefore = testFormsApi.getPublishedForm(secondFormPath).assertSuccess().body.publicationId

		changeAndPublishGlobalTranslations(authToken)

		val firstPublishedFormAfter = testFormsApi.getPublishedForm(firstFormPath).assertSuccess().body
		val secondPublishedFormAfter = testFormsApi.getPublishedForm(secondFormPath).assertSuccess().body
		val publishedForms = testFormsApi.getPublishedForms().assertSuccess().body

		assertNotEquals(firstPublicationIdBefore, firstPublishedFormAfter.publicationId)
		assertNotEquals(secondPublicationIdBefore, secondPublishedFormAfter.publicationId)
		assertEquals(firstPublishedFormAfter.publicationId, publishedForms.first { it.path == firstFormPath }.publicationId)
		assertEquals(secondPublishedFormAfter.publicationId, publishedForms.first { it.path == secondFormPath }.publicationId)
	}

	private fun createAndPublishForm(
		authToken: String,
		skjemanummer: String,
		title: String,
		withTranslation: Boolean = false,
	): String {
		val form = testFormsApi.createForm(
			FormsTestdata.newFormRequest(
				skjemanummer = skjemanummer,
				title = title,
			),
			authToken,
		).assertSuccess().body
		val formPath = form.path!!

		if (withTranslation) {
			testFormsApi.createFormTranslation(
				formPath,
				NewFormTranslationRequestDto(
					key = "$title-key",
					nb = "$title nb",
					nn = "$title nn",
					en = "$title en",
				),
				authToken,
			).assertSuccess()
		}

		testFormsApi.publishForm(formPath, form.revision!!, authToken, LanguageCode.entries).assertSuccess()
		return formPath
	}

	private fun changeAndPublishGlobalTranslations(authToken: String): String {
		val translation = testFormsApi.getGlobalTranslations().assertSuccess().body.first { it.key == "Fornavn" }
		val updatedEnTranslation = "${translation.en.orEmpty()} global-change"
		testFormsApi.putGlobalTranslation(
			translation.id,
			translation.revision!!,
			UpdateGlobalTranslationRequest(
				nb = translation.nb,
				en = updatedEnTranslation,
				nn = translation.nn,
			),
			authToken,
		).assertSuccess()
		testFormsApi.publishGlobalTranslations(authToken).assertHttpStatus(HttpStatus.CREATED)
		return updatedEnTranslation
	}

}
