package no.nav.forms.forms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.FilterChain

class FormClearGuardTest {
    @Test
    fun `production profile rejects before the rest of the filter chain`() {
        verifyDenied(MockEnvironment().apply { setActiveProfiles("prod") })
    }

    @Test
    fun `production cluster rejects before the rest of the filter chain`() {
        verifyDenied(MockEnvironment().withProperty("NAIS_CLUSTER_NAME", "prod-gcp"))
    }

    @Test
    fun `preprod reaches authentication and body parsing`() {
        val response = MockHttpServletResponse()
        var continued = false
        FormClearGuard(MockEnvironment().apply { setActiveProfiles("preprod") }).doFilter(
            MockHttpServletRequest("POST", "/api/database-cleanup/jobs"), response,
            FilterChain { _, _ -> continued = true }
        )
        assertTrue(continued)
    }

    private fun verifyDenied(environment: MockEnvironment) {
        val response = MockHttpServletResponse()
        var continued = false
        FormClearGuard(environment).doFilter(
            MockHttpServletRequest("POST", "/api/database-cleanup/jobs").apply {
                setContent("{malformed".toByteArray())
            }, response,
            FilterChain { _, _ -> continued = true }
        )
        assertEquals(403, response.status)
        assertFalse(continued)
    }
}
