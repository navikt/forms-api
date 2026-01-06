package no.nav.forms.staticpdfs

import no.nav.forms.ApplicationTest
import no.nav.forms.testutils.createAdminToken
import no.nav.forms.testutils.createUserToken
import no.nav.forms.utils.PdfLanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.http.HttpStatus
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticPdfControllerTest : ApplicationTest() {

	@Test
	fun testUploadPdf() {
		val authToken = mockOAuth2Server.createAdminToken()
		testFormsApi.uploadStaticPdf(
			formPath = "nav123456",
			languageCode = "nb",
			authToken = authToken,
		)
			.assertSuccess()
			.body.let {
				assertEquals("nb", it.languageCode)
				assertNotNull(it.fileName)
				assertNotNull(it.createdBy)
				assertNotNull(it.createdAt)
			}
	}

	@Test
	fun testUploadPdfWithoutToken() {
		testFormsApi.uploadStaticPdf(
			formPath = "nav123456",
			languageCode = "nb",
			authToken = null,
		)
			.assertHttpStatus(HttpStatus.UNAUTHORIZED)
			.errorBody.let {
				assertEquals("Unauthorized", it.errorMessage)
			}
	}

	@Test
	fun testUploadPdfWithoutAdminRole() {
		val authToken = mockOAuth2Server.createUserToken()
		testFormsApi.uploadStaticPdf(
			formPath = "nav123456",
			languageCode = "nb",
			authToken = authToken,
		)
			.assertHttpStatus(HttpStatus.FORBIDDEN)
			.errorBody.let {
				assertEquals("Forbidden", it.errorMessage)
			}
	}

	@Test
	fun testDeletePdf() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = authToken,
		).assertSuccess()
		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertFalse { it.isEmpty() }
			}

		testFormsApi.deleteStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = authToken,
		).assertSuccess()

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertTrue { it.isEmpty() }
			}
	}

	@Test
	fun testDeletePdfWithoutToken() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = authToken,
		).assertSuccess()
		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertFalse { it.isEmpty() }
			}

		testFormsApi.deleteStaticPdf(
			formPath = formPath,
			languageCode = "nb",
		).assertHttpStatus(HttpStatus.UNAUTHORIZED)

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertFalse { it.isEmpty() }
			}
	}

	@Test
	fun testDeletePdfWithoutAdminRole() {
		val adminToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = adminToken,
		).assertSuccess()
		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertFalse { it.isEmpty() }
			}

		val userToken = mockOAuth2Server.createUserToken()
		testFormsApi.deleteStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = userToken,
		).assertHttpStatus(HttpStatus.FORBIDDEN)

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertFalse { it.isEmpty() }
			}
	}

	@Test
	fun testGetStaticPdfs() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			authToken = authToken,
		).assertSuccess()

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertEquals(1, it.size)
			}
	}

	@Test
	fun testReplacingPdf() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			fileName = "small.pdf",
			authToken = authToken,
		).assertSuccess()
		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertEquals(1, it.size)
				assertEquals("small.pdf", it[0].fileName)
			}

		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "nb",
			fileName = "other-small.pdf",
			authToken = authToken,
		).assertSuccess()

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let {
				assertEquals(1, it.size)
				assertEquals("other-small.pdf", it[0].fileName)
			}
	}

	@Test
	fun testUploadPdfForMultipleLanguages() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		val allowedLanguageCodes = PdfLanguageCode.entries.map { it.value }

		allowedLanguageCodes.forEach {
			testFormsApi.uploadStaticPdf(
				formPath = formPath,
				languageCode = it,
				authToken = authToken,
			).assertSuccess()
		}

		testFormsApi.getStaticPdfs(formPath = formPath)
			.assertSuccess()
			.body.let { uploadedPdfs ->
				assertEquals(allowedLanguageCodes.size, uploadedPdfs.size)
				assertEquals(allowedLanguageCodes.toSet(), uploadedPdfs.map { it.languageCode }.toSet())
			}
	}

	@Test
	fun testGetPdf() {
		val authToken = mockOAuth2Server.createAdminToken()
		val formPath = "nav123456"
		testFormsApi.uploadStaticPdf(
			formPath = formPath,
			languageCode = "fr",
			authToken = authToken,
		).assertSuccess()

		testFormsApi.getStaticPdfContent(formPath, "fr")
			.assertSuccess()
			.body.let { assertTrue { it.isNotEmpty() } }
	}

	@Test
	fun testGetNonExistingPdf() {
		val formPath = "nav123456"
		testFormsApi.getStaticPdfContent(formPath, "fr")
			.assertHttpStatus(HttpStatus.NOT_FOUND)
	}

}
