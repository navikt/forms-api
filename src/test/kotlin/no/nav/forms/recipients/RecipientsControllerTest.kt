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
import org.springframework.boot.resttestclient.getForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class RecipientsControllerTest : ApplicationTest() {

	private val testUserName = "Navnesen, Navn"

	@Test
	fun testGetRecipients() {
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
		val response = restTemplate.exchange(
			url.toURI(),
			HttpMethod.POST,
			HttpEntity(requestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName))),
			RecipientDto::class.java
		)
		assertTrue(response.statusCode.is2xxSuccessful)
		assertNotNull(response.body?.recipientId)
		assertEquals(requestBody.name, response.body?.name)
		assertEquals(testUserName, response.body?.createdBy)
	}

	@Test
	fun testPutRecipient() {
		val url = "$baseUrl/v1/recipients/1"
		val requestBody = UpdateRecipientRequest(
			"NAV Nytt navn",
			"Postboks 99",
			"6425",
			"Molde",
		)
		val response = restTemplate.exchange(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(requestBody, httpHeaders(mockOAuth2Server.createMockToken(userName = testUserName))),
			String::class.java
		)
		assertTrue(response.statusCode.is2xxSuccessful)
		val getResponse = restTemplate.getForEntity<RecipientDto>(url.toURI())
		assertNotNull(getResponse.body?.recipientId)
		assertEquals(requestBody.name, getResponse.body?.name)
		assertEquals(requestBody.poBoxAddress, getResponse.body?.poBoxAddress)
		assertEquals(requestBody.postalCode, getResponse.body?.postalCode)
		assertEquals(requestBody.postalName, getResponse.body?.postalName)
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
		val response = restTemplate.exchange(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(requestBody),
			String::class.java
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
		val response = restTemplate.exchange(
			url.toURI(),
			HttpMethod.PUT,
			HttpEntity(requestBody, httpHeaders(tokenNotAdmin)),
			String::class.java
		)
		assertEquals(HttpStatus.FORBIDDEN.value(), response.statusCode.value())
	}

	private fun httpHeaders(token: String): HttpHeaders {
		val headers = HttpHeaders()
		headers.add("Authorization", "Bearer $token")
		return headers
	}

}
