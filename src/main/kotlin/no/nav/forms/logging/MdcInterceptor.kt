package no.nav.forms.logging

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.*

@Component
class MdcInterceptor : HandlerInterceptor {

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		val correlationId = request.getHeader("x-correlation-id") ?: UUID.randomUUID().toString()
		MDC.put(CORRELATION_ID, correlationId)
		return true
	}

	override fun afterCompletion(
		request: HttpServletRequest,
		response: HttpServletResponse,
		handler: Any,
		ex: Exception?
	) = MDC.remove(CORRELATION_ID)

	companion object {
		const val CORRELATION_ID = "correlation_id"
	}

}
