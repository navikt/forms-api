package no.nav.forms.translations

import no.nav.forms.ApplicationTest
import no.nav.forms.model.UpdateGlobalTranslationRequest
import no.nav.forms.testutils.MOCK_USER_GROUP_ID
import no.nav.forms.testutils.createMockToken
import no.nav.forms.utils.LanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
		bokmal.keys.none { it == tFornavn.key }
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

}
