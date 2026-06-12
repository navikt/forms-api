package no.nav.forms.translations

import jakarta.persistence.EntityManagerFactory
import no.nav.forms.ApplicationTest
import no.nav.forms.model.NewGlobalTranslationRequest
import no.nav.forms.model.UpdateGlobalTranslationRequest
import no.nav.forms.testutils.createMockToken
import no.nav.forms.translations.global.EditGlobalTranslationsService
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class EditGlobalTranslationsReadPathImplementationTest : ApplicationTest() {

	@Autowired
	lateinit var entityManagerFactory: EntityManagerFactory

	@Autowired
	lateinit var editGlobalTranslationsService: EditGlobalTranslationsService

	@Test
	fun testGetLatestRevisionsUsesBoundedQueryCount() {
		val authToken = mockOAuth2Server.createMockToken()

		(1..5).forEach { index ->
			val createdTranslation = testFormsApi.createGlobalTranslation(
				NewGlobalTranslationRequest(
					key = "translation-$index",
					tag = "skjematekster",
					nb = "bokmal-$index",
				),
				authToken
			).assertSuccess().body

			testFormsApi.putGlobalTranslation(
				createdTranslation.id,
				createdTranslation.revision!!,
				UpdateGlobalTranslationRequest(
					nb = "bokmal-$index-updated",
					en = "english-$index",
				),
				authToken
			).assertSuccess()
		}

		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()

		val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
		statistics.clear()

		val latestRevisions = editGlobalTranslationsService.getLatestRevisions()

		assertEquals(5, latestRevisions.size)
		assertTrue(
			statistics.prepareStatementCount <= 2,
			"Expected at most 2 SQL statements, but saw ${statistics.prepareStatementCount}"
		)
	}
}
