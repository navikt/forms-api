package no.nav.forms.exceptions

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import no.nav.forms.ApplicationTest
import no.nav.forms.logging.MdcInterceptor
import no.nav.forms.model.ErrorResponseDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus

class RestExceptionHandlerTest : ApplicationTest() {

	private val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java) as Logger
	private val logAppender = ListAppender<ILoggingEvent>()

	@BeforeEach
	fun captureLogs() {
		logAppender.start()
		logger.addAppender(logAppender)
	}

	@AfterEach
	fun stopCapturingLogs() {
		logger.detachAppender(logAppender)
		logAppender.stop()
	}

	@ParameterizedTest
	@ValueSource(strings = ["/", "/missing-resource.css"])
	fun `missing static resources return not found without error logging`(path: String) {
		val response = restTemplate.getForEntity(path, ErrorResponseDto::class.java)

		assertFalse(logAppender.list.any { it.level == Level.ERROR }, "Missing resources must not be logged as errors")
		assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
		assertEquals(HttpStatus.NOT_FOUND.reasonPhrase, response.body?.errorMessage)
		assertFalse(response.body?.correlationId.isNullOrBlank())
	}

	@Test
	fun `unexpected exceptions still return internal server error and log an error`() {
		val previousCorrelationId = MDC.get(MdcInterceptor.CORRELATION_ID)
		MDC.put(MdcInterceptor.CORRELATION_ID, "test-correlation-id")
		try {
			val response = RestExceptionHandler().handleGenericException(UnsupportedOperationException("Test failure"))

			assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
			assertEquals("Something went wrong", response.body?.errorMessage)
			assertEquals("test-correlation-id", response.body?.correlationId)
			assertTrue(logAppender.list.any { it.level == Level.ERROR })
		} finally {
			if (previousCorrelationId == null) {
				MDC.remove(MdcInterceptor.CORRELATION_ID)
			} else {
				MDC.put(MdcInterceptor.CORRELATION_ID, previousCorrelationId)
			}
		}
	}
}
