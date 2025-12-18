package no.nav.forms

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.forms.model.NewGlobalTranslationRequest
import no.nav.forms.testutils.createMockToken
import no.nav.forms.translations.testdata.GlobalTranslationsTestdata
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.token.support.spring.test.EnableMockOAuth2Server
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT
)
@EnableMockOAuth2Server
abstract class ApplicationTest(val setupPublishedGlobalTranslations: Boolean = false) {

	@Autowired
	lateinit var restTemplate: TestRestTemplate

	@Autowired
	lateinit var mockOAuth2Server: MockOAuth2Server

	@Autowired
	lateinit var objectMapper: ObjectMapper

	@Autowired
	private lateinit var flyway: Flyway

	final val baseUrl = "http://localhost:9082"

	lateinit var testFormsApi: TestFormsApi

	private var _publishedGlobalTranslations: Map<String, NewGlobalTranslationRequest> = emptyMap()

	@BeforeEach
	fun setup() {
		flyway.clean()
		flyway.migrate()
		testFormsApi = TestFormsApi(baseUrl, restTemplate, objectMapper)

		if (setupPublishedGlobalTranslations) {
			val authToken = mockOAuth2Server.createMockToken()
			GlobalTranslationsTestdata.translations.values.forEach {
				testFormsApi.createGlobalTranslation(it, authToken).assertSuccess()
			}
			testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
			_publishedGlobalTranslations = GlobalTranslationsTestdata.translations
		}
	}

	fun getPublishedGlobalTranslations(): Map<String, NewGlobalTranslationRequest> = _publishedGlobalTranslations

}
