package no.nav.forms.recipients

import no.nav.forms.ApplicationTest
import no.nav.forms.model.NewRecipientRequest
import no.nav.forms.model.RecipientDto
import no.nav.forms.model.UpdateRecipientRequest
import no.nav.forms.testutils.MOCK_USER_GROUP_ID
import no.nav.forms.testutils.createMockToken
import no.nav.forms.testutils.toURI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.resttestclient.exchange
import org.springframework.boot.resttestclient.getForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class RecipientsControllerTest : ApplicationTest() {

	private val testUserName = "Navnesen, Navn"

	@Test
	fun testGetRecipients() {
		val requestBody = NewRecipientRequest(
			"NAV Skanning",
			"Postboks 1",
			"0591",
			"Oslo",
		)
		restTemplate.exchange<RecipientDto>(
			"$baseUrl/v1/recipients".toURI(),
			HttpMethod.POST,
			HttpEntity(requestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName)))
		)
		restTemplate.exchange<RecipientDto>(
			"$baseUrl/v1/recipients".toURI(),
			HttpMethod.POST,
			HttpEntity(requestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName)))
		)
		val url = "$baseUrl/v1/recipients"
		val response = restTemplate.getForEntity<List<RecipientDto>>(url.toURI())
		assertEquals(2, response.body?.size)
	}

	@Test
	fun testPostRecipient() {
		val url = "$baseUrl/v1/recipients"
		val requestBody = NewRecipientRequest(
			"NAV Skanning",
			"Postboks 1",
			"0591",
			"Oslo",
		)
		val response = restTemplate.exchange<RecipientDto>(
			url.toURI(),
			HttpMethod.POST,
			HttpEntity(requestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName)))
		)
		assertTrue(response.statusCode.is2xxSuccessful)
		assertNotNull(response.body?.recipientId)
		assertEquals(requestBody.name, response.body?.name)
		assertEquals(testUserName, response.body?.createdBy)
	}

	@Test
	fun testUpdateRecipient() {
		val originalRequestBody = NewRecipientRequest(
			"NAV Skanning",
			"Postboks 1",
			"0591",
			"Oslo",
		)
		val originalResponse = restTemplate.exchange<RecipientDto>(
			"$baseUrl/v1/recipients".toURI(),
			HttpMethod.POST,
			HttpEntity(originalRequestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName)))
		)

		val url = "$baseUrl/v1/recipients/${originalResponse.body?.recipientId}"
		val updateRequestBody = UpdateRecipientRequest(
			"NAV Nytt navn",
			"Postboks 99",
			"6425",
			"Molde",
		)
		val updateResponse = restTemplate.exchange<String>(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(updateRequestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName)))
		)
		assertTrue(updateResponse.statusCode.is2xxSuccessful)
		val getResponse = restTemplate.getForEntity<RecipientDto>(url.toURI())
		assertNotNull(getResponse.body?.recipientId)
		assertEquals(updateRequestBody.name, getResponse.body?.name)
		assertEquals(updateRequestBody.poBoxAddress, getResponse.body?.poBoxAddress)
		assertEquals(updateRequestBody.postalCode, getResponse.body?.postalCode)
		assertEquals(updateRequestBody.postalName, getResponse.body?.postalName)
		assertEquals(testUserName, getResponse.body?.changedBy)
	}

	@Test
	fun testPutRecipientWithoutToken() {
		val url = "$baseUrl/v1/recipients/1"
		val requestBody = UpdateRecipientRequest(
			"NAV Nytt navn",
			"Postboks 99",
			"6425",
			"Molde",
		)
		val response = restTemplate.exchange<String>(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(requestBody)
		)
		assertEquals(HttpStatus.UNAUTHORIZED.value(), response.statusCode.value())
	}

	@Test
	fun testPutRecipientWithoutAdminRole() {
		val url = "$baseUrl/v1/recipients/1"
		val requestBody = UpdateRecipientRequest(
			"NAV Nytt navn",
			"Postboks 99",
			"6425",
			"Molde",
		)
		val tokenNotAdmin = mockOAuth2Server.createMockToken(groups = listOf(MOCK_USER_GROUP_ID))
		val response = restTemplate.exchange<String>(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(requestBody, httpHeaders(tokenNotAdmin))
		)
		assertEquals(HttpStatus.FORBIDDEN.value(), response.statusCode.value())
	}

	private fun httpHeaders(token: String): HttpHeaders {
		val headers = HttpHeaders()
		headers.add("Authorization", "Bearer $token")
		return headers
	}

}
