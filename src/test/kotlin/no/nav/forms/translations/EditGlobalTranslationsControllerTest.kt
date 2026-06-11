package no.nav.forms.translations

import no.nav.forms.ApplicationTest
import no.nav.forms.model.*
import no.nav.forms.testutils.createAdminToken
import no.nav.forms.testutils.MOCK_USER_GROUP_ID
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.toFormPath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus


class EditGlobalTranslationsControllerTest : ApplicationTest() {

	@Test
	fun testPostGlobalTranslation() {
		val authToken = mockOAuth2Server.createMockToken()
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
			nn = "Fornamn"
		)
		val response = testFormsApi.createGlobalTranslation(request, authToken).assertSuccess()
		assertEquals(request.key, response.body.key)
		assertEquals(request.nb, response.body.nb)
		assertEquals(request.nn, response.body.nn)
		assertNull(response.body.en)
		assertEquals(1, response.body.revision)
	}

	@Test
	fun testDuplicatePostGlobalTranslationIsRejected() {
		val authToken = mockOAuth2Server.createMockToken()
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
			nn = "Fornamn"
		)
		testFormsApi.createGlobalTranslation(request, authToken).assertSuccess()
		testFormsApi.createGlobalTranslation(request, authToken).assertClientError()
	}

	@Test
	fun onlyAdminUsersAreAllowedToCreateGlobalTranslation() {
		val tokenNotAdmin = mockOAuth2Server.createMockToken(groups = listOf(MOCK_USER_GROUP_ID))
		val response = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(
				key = "Fornavn",
				tag = "skjematekster",
				nb = "Fornavn"
			),
			tokenNotAdmin
		).assertHttpStatus(HttpStatus.FORBIDDEN)
		assertEquals("Forbidden", response.errorBody.errorMessage)
	}

	@Test
	fun testChangeOfGlobalTranslation() {
		val adminToken = mockOAuth2Server.createMockToken()

		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
			nn = "Fornamn"
		)
		val postResponse = testFormsApi.createGlobalTranslation(createRequest, adminToken)
			.assertSuccess()

		val translationBody = postResponse.body
		assertEquals(1, translationBody.revision)

		val putRequest = UpdateGlobalTranslationRequest(
			nb = "Fornavn",
			nn = "Fornamn",
			en = "Surname"
		)
		val putResponse =	testFormsApi
			.putGlobalTranslation(translationBody.id, translationBody.revision!!, putRequest, adminToken)
			.assertSuccess()
		assertEquals(2, putResponse.body.revision)
		assertEquals("skjematekster", putResponse.body.tag)
	}

	@Test
	fun testUpdateGlobalTranslationTag() {
		val adminToken = mockOAuth2Server.createMockToken()

		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
		)
		val postResponse = testFormsApi.createGlobalTranslation(createRequest, adminToken)
			.assertSuccess()

		val translationBody = postResponse.body
		assertEquals(1, translationBody.revision)

		val putRequest = UpdateGlobalTranslationRequest(
			nb = "Fornavn",
			tag = "grensesnitt",
		)
		val putResponse =	testFormsApi
			.putGlobalTranslation(translationBody.id, translationBody.revision!!, putRequest, adminToken)
			.assertSuccess()
		assertEquals(2, putResponse.body.revision)
		assertEquals("grensesnitt", putResponse.body.tag)
	}

	@Test
	fun testConflictWhenUpdatingGlobalTranslationOnSameRevision() {
		val adminToken = mockOAuth2Server.createMockToken()

		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
			nn = "Fornamn"
		)
		val postResponse = testFormsApi.createGlobalTranslation(createRequest, adminToken)
			.assertSuccess()

		val translationBody = postResponse.body
		val revision1 = translationBody.revision!!
		assertEquals(1, revision1)

		val firstPutResponse = testFormsApi.putGlobalTranslation(
			translationBody.id, revision1, UpdateGlobalTranslationRequest(
				nb = "Fornavn",
				nn = "Fornamn",
				en = "Surname"
			), adminToken
		).assertSuccess()
		val updatedTranslation = firstPutResponse.body
		assertEquals(2, updatedTranslation.revision)

		val secondUpdateResponse = testFormsApi.putGlobalTranslation(
			translationBody.id, revision1, UpdateGlobalTranslationRequest(
				nb = "Fornavn",
				nn = "Feil oversettelse",
				en = "Wrong translation"
			), adminToken
		).assertClientError()
		assertEquals("Conflict", secondUpdateResponse.errorBody.errorMessage)

		val latestRevisionsResponse = testFormsApi.getGlobalTranslations()
		assertEquals(1, latestRevisionsResponse.body.size)

		val body = latestRevisionsResponse.body
		assertEquals("Fornamn", body[0].nn)
		assertEquals("Surname", body[0].en)
	}

	@Test
	fun testUpdatingGlobalTranslationOnErroneousRevision() {
		val adminToken = mockOAuth2Server.createMockToken()

		val postResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(
				key = "Ja",
				tag = "skjematekster",
				nb = "Ja",
			),
			adminToken
		).assertSuccess()
		assertEquals(1, postResponse.body.revision)

		val firstPutResponse = testFormsApi.putGlobalTranslation(
			postResponse.body.id, 2,
			UpdateGlobalTranslationRequest(
				nb = "Ja",
				en = "Yes"
			),
			adminToken
		).assertClientError()
		assertEquals("Conflict", firstPutResponse.errorBody.errorMessage)

		val globalTranslationsResponse = testFormsApi.getGlobalTranslations().assertSuccess()
		assertEquals(1, globalTranslationsResponse.body.size)
		assertEquals("Ja", globalTranslationsResponse.body[0].nb)
		assertNull(globalTranslationsResponse.body[0].en)
	}

	@Test
	fun testGetLatestRevisions() {
		val authToken = mockOAuth2Server.createMockToken()

		val createFornavnResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(
				key = "Fornavn",
				tag = "skjematekster",
				nb = "Fornavn"
			), authToken
		).assertSuccess()

		val createEtternavnResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(
				key = "Etternavn",
				tag = "skjematekster",
				nb = "Etternavn"
			), authToken
		).assertSuccess()

		testFormsApi.putGlobalTranslation(
			createFornavnResponse.body.id,
			createFornavnResponse.body.revision!!,
			UpdateGlobalTranslationRequest(
				nb = "Fornavn",
				nn = "Fornamn",
				en = "Given name"
			), authToken
		).assertSuccess()

		testFormsApi.putGlobalTranslation(
			createEtternavnResponse.body.id,
			createEtternavnResponse.body.revision!!,
			UpdateGlobalTranslationRequest(
				nb = "Etternavn",
				nn = "Etternamn",
				en = "Surname"
			), authToken
		).assertSuccess()

		val latestRevisionsResponse = testFormsApi.getGlobalTranslations().assertSuccess()
		assertEquals(2, latestRevisionsResponse.body.size)

		val givenNameLatestRevision =
			latestRevisionsResponse.body.firstOrNull { it.key == createFornavnResponse.body.key }
		val surnameLatestRevision =
			latestRevisionsResponse.body.firstOrNull { it.key == createEtternavnResponse.body.key }

		assertNotNull(givenNameLatestRevision)
		assertNotNull(surnameLatestRevision)

		assertEquals("Given name", givenNameLatestRevision!!.en)
		assertEquals("Surname", surnameLatestRevision!!.en)
	}

	@Test
	fun testDeleteOk() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
		)
		val createResponse = testFormsApi.createGlobalTranslation(createRequest, authToken).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id, authToken)
			.assertSuccess()

		val getResponse = testFormsApi.getGlobalTranslations().assertSuccess()
		val translation = getResponse.body.firstOrNull { it.key == createResponse.body.key }
		assertNull(translation)
		assertEquals(0, getResponse.body.size)
	}

	@Test
	fun testDeleteFailsWhenNoToken() {
		val authToken = mockOAuth2Server.createMockToken()
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
		)
		val createResponse = testFormsApi.createGlobalTranslation(request, authToken).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
	}

	@Test
	fun testDeleteFailsWhenNoAdminToken() {
		val adminAuthToken = mockOAuth2Server.createMockToken()
		val userAuthToken = mockOAuth2Server.createMockToken(groups = listOf(MOCK_USER_GROUP_ID))
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
		)
		val createResponse = testFormsApi.createGlobalTranslation(request, adminAuthToken).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id, userAuthToken)
			.assertHttpStatus(HttpStatus.FORBIDDEN)
	}

	@Test
	fun testDeleteFailsWhenFormTranslationReferencesIt() {
		val authToken = mockOAuth2Server.createMockToken()
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
		)
		val createResponse = testFormsApi.createGlobalTranslation(request, authToken).assertSuccess()
		val newFormRequest = FormsTestdata.newFormRequest(skjemanummer = "NAV 12-34.56")
		testFormsApi.createForm(newFormRequest, authToken)
			.assertSuccess()
		testFormsApi.createFormTranslation(
			newFormRequest.skjemanummer.toFormPath(),
			NewFormTranslationRequestDto(
				"Fornavn",
				globalTranslationId = createResponse.body.id
			),
			authToken
		).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id, authToken)
			.assertHttpStatus(HttpStatus.CONFLICT)
	}

	@Test
	fun testDeleteDoesNotFailWhenFormTranslationOnlyReferencedItInThePast() {
		val authToken = mockOAuth2Server.createMockToken()
		val request = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
		)
		val createResponse = testFormsApi.createGlobalTranslation(request, authToken).assertSuccess()

		val newFormRequest = FormsTestdata.newFormRequest(skjemanummer = "NAV 12-34.56")
		testFormsApi.createForm(newFormRequest, authToken)
			.assertSuccess()
		val createFormTranslationResponse = testFormsApi.createFormTranslation(
			newFormRequest.skjemanummer.toFormPath(),
			NewFormTranslationRequestDto(
				"Fornavn",
				globalTranslationId = createResponse.body.id
			),
			authToken
		).assertSuccess()
		testFormsApi.updateFormTranslation(
			"nav123456",
			createFormTranslationResponse.body.id,
			createFormTranslationResponse.body.revision!!,
			UpdateFormTranslationRequest(
				globalTranslationId = null,
				nb = "Fornavn",
				nn = "Fornamn",
				en = "Given name"
			),
			authToken
		).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id, authToken)
			.assertSuccess()
	}

	@Test
	fun testUpdateOfDeletedTranslation() {
		val adminToken = mockOAuth2Server.createMockToken()

		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			nb = "Fornavn",
			nn = "Fornamn"
		)
		val postResponse = testFormsApi.createGlobalTranslation(createRequest, adminToken).assertSuccess()

		val translationBody = postResponse.body
		assertEquals(1, translationBody.revision)

		testFormsApi.deleteGlobalTranslation(translationBody.id, adminToken)
			.assertSuccess()

		val putRequest = UpdateGlobalTranslationRequest(
			nb = "Fornavn",
			nn = "Fornamn",
			en = "Surname"
		)
		val putResponse =
			testFormsApi.putGlobalTranslation(translationBody.id, translationBody.revision!!, putRequest, adminToken)
		assertEquals(HttpStatus.NOT_FOUND.value(), putResponse.statusCode.value())
	}

	@Test
	fun testRecreateAfterDelete() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			en = "Given name"
		)
		val createResponse = testFormsApi.createGlobalTranslation(createRequest, authToken).assertSuccess()
		assertEquals(1, createResponse.body.revision)
		assertEquals(createRequest.tag, createResponse.body.tag)
		assertEquals(createRequest.nb, createResponse.body.nb)
		assertEquals(createRequest.nn, createResponse.body.nn)
		assertEquals(createRequest.en, createResponse.body.en)

		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()

		testFormsApi.deleteGlobalTranslation(createResponse.body.id, authToken)
			.assertSuccess()

		val recreateRequest = createRequest.copy(nb = "Fornavn på bokmål", tag = "grensesnitt", en = null, nn = null)
		val recreateResponse = testFormsApi.createGlobalTranslation(recreateRequest, authToken).assertSuccess()
		val recreateBody = recreateResponse.body
		assertEquals(2, recreateBody.revision)
		assertEquals(recreateRequest.tag, recreateBody.tag)
		assertEquals(recreateRequest.nb, recreateBody.nb)
		assertEquals(recreateRequest.nn, recreateBody.nn)
		assertEquals(recreateRequest.en, recreateBody.en)
		assertNotNull(recreateBody.publishedAt)
		assertNotNull(recreateBody.publishedBy)

		val getResponse = testFormsApi.getGlobalTranslations().assertSuccess()
		val translation = getResponse.body.firstOrNull { it.key == createResponse.body.key }
		assertNotNull(translation)
		assertEquals(recreateRequest.nb, translation!!.nb)
		assertEquals(recreateRequest.nn, translation.nn)
		assertEquals(recreateRequest.en, translation.en)
		assertEquals(recreateRequest.tag, translation.tag)
		assertNotNull(translation.publishedAt)
		assertNotNull(translation.publishedBy)
	}

	@Test
	fun testPublishedAtIsCorrectAfterUpdate() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			en = "Given name"
		)
		val createResponse = testFormsApi.createGlobalTranslation(createRequest, authToken).assertSuccess()

		// First publish
		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
		val publication1 = testFormsApi.getGlobalTranslationPublication(listOf(LanguageCode.EN.value)).body

		// Update the translation after publish
		val putRequest1 = UpdateGlobalTranslationRequest(
			nb = "Fornavn",
			nn = "Fornamn",
			en = "Given name with change"
		)
		val putResponse1 = testFormsApi
			.putGlobalTranslation(createResponse.body.id, createResponse.body.revision!!, putRequest1, authToken)
			.assertSuccess()
		assertEquals(publication1.publishedAt, putResponse1.body.publishedAt)

		val allGlobalTranslations = fetchGlobalTranslations()

		// Verify updated translation data with published information
		val globalTranslation = allGlobalTranslations.firstOrNull()
		assertNotNull(globalTranslation)
		assertEquals(publication1.publishedAt, globalTranslation?.publishedAt)
		assertNotNull(globalTranslation?.publishedBy)
		assertTrue(
			globalTranslation?.changedAt?.isAfter(publication1.publishedAt) == true,
			"ChangedAt timestamp should be after publishedAt"
		)

		// Update the translation after first publish
		val putRequest2 = UpdateGlobalTranslationRequest(
			nb = "Fornavn",
			nn = "Fornamn",
			en = "Given name with change 2"
		)
		val putResponse2 = testFormsApi
			.putGlobalTranslation(putResponse1.body.id, putResponse1.body.revision!!, putRequest2, authToken)
			.assertSuccess()
		assertEquals(publication1.publishedAt, putResponse2.body.publishedAt)

		// Second publish
		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
		val publication2 = testFormsApi.getGlobalTranslationPublication(listOf(LanguageCode.EN.value)).body

		val allGlobalTranslationsFinal = fetchGlobalTranslations()
		val globalTranslationFinal = allGlobalTranslationsFinal.firstOrNull()
		assertNotNull(globalTranslationFinal)
		assertEquals(publication2.publishedAt, globalTranslationFinal?.publishedAt)
	}

	@Test
	fun testPublishedAtIsCorrectAfterTwoPublications() {
		val authToken = mockOAuth2Server.createMockToken()
		val createRequest = NewGlobalTranslationRequest(
			key = "Fornavn",
			tag = "skjematekster",
			en = "Given name"
		)
		testFormsApi.createGlobalTranslation(createRequest, authToken).assertSuccess()

		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()

		val publication1 = testFormsApi.getGlobalTranslationPublication().body

		val allGlobalTranslations1 = fetchGlobalTranslations()
		val globalTranslationAfterFirstPublication = allGlobalTranslations1.firstOrNull()
		assertNotNull(globalTranslationAfterFirstPublication)
		assertEquals(
			publication1.publishedAt,
			globalTranslationAfterFirstPublication?.publishedAt,
			"Should still show published timestamp"
		)

		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
		val publication2 = testFormsApi.getGlobalTranslationPublication().body

		val allGlobalTranslations2 = fetchGlobalTranslations()
		val globalTranslationAfterSecondPublication = allGlobalTranslations2.firstOrNull()
		assertNotNull(globalTranslationAfterSecondPublication)
		assertEquals(
			publication2.publishedAt,
			globalTranslationAfterSecondPublication?.publishedAt,
			"Should still show published timestamp"
		)
	}

	@Test
	fun testPublishedByIsTakenFromLatestPublication() {
		val firstPublisherToken = mockOAuth2Server.createAdminToken(
			navIdent = "A123456",
			userName = "First Publisher"
		)
		val secondPublisherToken = mockOAuth2Server.createAdminToken(
			navIdent = "B123456",
			userName = "Second Publisher"
		)
		val createResponse = testFormsApi.createGlobalTranslation(
			NewGlobalTranslationRequest(
				key = "Greeting",
				tag = "skjematekster",
				nb = "Hei",
			),
			firstPublisherToken
		).assertSuccess()

		testFormsApi.publishGlobalTranslations(firstPublisherToken).assertSuccess()
		testFormsApi.putGlobalTranslation(
			createResponse.body.id,
			createResponse.body.revision!!,
			UpdateGlobalTranslationRequest(
				nb = "Hei igjen",
				en = "Hello again",
			),
			secondPublisherToken
		).assertSuccess()
		testFormsApi.publishGlobalTranslations(secondPublisherToken).assertSuccess()

		val translation = fetchGlobalTranslations().single()
		assertEquals("Second Publisher", translation.publishedBy)
	}

	@Test
	fun testGetLatestRevisionsPreservesInsertionOrder() {
		val authToken = mockOAuth2Server.createMockToken()
		val keys = listOf("first-key", "second-key", "third-key")

		keys.forEach { key ->
			testFormsApi.createGlobalTranslation(
				NewGlobalTranslationRequest(
					key = key,
					tag = "skjematekster",
					nb = key,
				),
				authToken
			).assertSuccess()
		}

		val latestRevisions = fetchGlobalTranslations()
		assertEquals(keys, latestRevisions.map(GlobalTranslationDto::key))
	}

	private fun fetchGlobalTranslations(): List<GlobalTranslationDto> {
		val globalTranslationsResponse = testFormsApi.getGlobalTranslations().assertSuccess()
		return globalTranslationsResponse.body
	}
}
