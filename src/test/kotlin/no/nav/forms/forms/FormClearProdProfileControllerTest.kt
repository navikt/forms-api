package no.nav.forms.forms

import no.nav.forms.ApplicationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test", "prod")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "no.nav.security.jwt.issuer.azuread.discoveryurl=http://localhost:\${mock-oauth2-server.port}/azuread/.well-known/openid-configuration",
        "no.nav.security.jwt.issuer.azuread.acceptedaudience=aud-localhost",
        "FORMS_API_AD_GROUP_USER=mock-user-group-id",
        "FORMS_API_AD_GROUP_ADMIN=mock-admin-group-id",
        "NAIS_DATABASE_FORMS_API_FORMS_API_DB_JDBC_URL=jdbc:postgresql://localhost/unused",
    ]
)
class FormClearProdProfileControllerTest : ApplicationTest() {
    @LocalServerPort private var port: Int = 0

    @Test
    fun `production profile rejects malformed unauthenticated request before parsing`() {
        val response = restTemplate.exchange<String>(
            "http://localhost:$port/api/database-cleanup/preview", HttpMethod.POST, HttpEntity("{not-json")
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange<String>(
            "http://localhost:$port/api/database-cleanup/jobs/active", HttpMethod.GET, HttpEntity.EMPTY
        ).statusCode)
    }
}
