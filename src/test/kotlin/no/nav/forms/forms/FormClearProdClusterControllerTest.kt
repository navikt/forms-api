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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["NAIS_CLUSTER_NAME=prod-gcp"]
)
class FormClearProdClusterControllerTest : ApplicationTest() {
    @LocalServerPort private var port: Int = 0

    @Test
    fun `production cluster rejects malformed unauthenticated request before parsing`() {
        val response = restTemplate.exchange<String>(
            "http://localhost:$port/api/form-clear/jobs", HttpMethod.POST, HttpEntity("{not-json")
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, restTemplate.exchange<String>(
            "http://localhost:$port/api/form-clear/jobs/active", HttpMethod.GET, HttpEntity.EMPTY
        ).statusCode)
    }
}
