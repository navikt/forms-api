package no.nav.forms.exceptions

import no.nav.forms.exceptions.db.getFormsApiDbError
import no.nav.forms.logging.MdcInterceptor
import no.nav.forms.model.ErrorResponseDto
import no.nav.forms.utils.throwable.hasCause
import no.nav.security.token.support.core.exceptions.JwtTokenInvalidClaimException
import no.nav.security.token.support.core.exceptions.JwtTokenMissingException
import no.nav.security.token.support.spring.validation.interceptor.JwtTokenUnauthorizedException
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.orm.jpa.JpaSystemException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class RestExceptionHandler {

	val logger: Logger = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler
	fun handleResourceNotFound(exception: ResourceNotFoundException): ResponseEntity<ErrorResponseDto> {
		logger.info("${exception.message} (id=${exception.resourceId})", exception)
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponseDto(exception.message, getCorrelationId()))
	}

	@ExceptionHandler
	fun handleNotImplementedError(error: NotImplementedError): ResponseEntity<ErrorResponseDto> {
		logger.info(error.message, error)
		val status = HttpStatus.NOT_IMPLEMENTED
		return ResponseEntity.status(status).body(ErrorResponseDto(status.reasonPhrase, getCorrelationId()))
	}

	@ExceptionHandler(
		JwtTokenMissingException::class,
		JwtTokenUnauthorizedException::class,
	)
	fun handlJwtTokenUnauthorized(exception: Exception): ResponseEntity<ErrorResponseDto> {
		val status = HttpStatus.UNAUTHORIZED
		logger.info(exception.message ?: status.reasonPhrase, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(status.reasonPhrase, getCorrelationId()))
	}

	@ExceptionHandler
	fun handlJwtTokenInvalidClaimException(exception: JwtTokenInvalidClaimException): ResponseEntity<ErrorResponseDto> {
		val status = HttpStatus.FORBIDDEN
		logger.info(exception.message ?: status.reasonPhrase, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(status.reasonPhrase, getCorrelationId()))
	}

	@ExceptionHandler
	fun handleConflict(exception: ConflictException): ResponseEntity<ErrorResponseDto> {
		val status = HttpStatus.CONFLICT
		logger.info(exception.message, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(status.reasonPhrase, getCorrelationId()))
	}

	@ExceptionHandler(
		DataIntegrityViolationException::class,
		IllegalArgumentException::class,
	)
	fun handleBadRequest(exception: Exception): ResponseEntity<ErrorResponseDto> {
		val status = HttpStatus.BAD_REQUEST
		logger.info(exception.message ?: status.reasonPhrase, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(exception.message ?: status.reasonPhrase, getCorrelationId()))
	}

	@ExceptionHandler
	fun handleJpaSystemException(exception: JpaSystemException): ResponseEntity<ErrorResponseDto> {
		val dbError = exception.getFormsApiDbError()
		val status = dbError?.httpStatus ?: HttpStatus.INTERNAL_SERVER_ERROR
		val errorMessage = dbError?.message ?: exception.message ?: status.reasonPhrase
		logger.info(errorMessage, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(errorMessage, getCorrelationId()))
	}

	@ExceptionHandler
	fun handleMissingRequestHeaderException(exception: MissingRequestHeaderException): ResponseEntity<ErrorResponseDto> {
		val status = HttpStatus.BAD_REQUEST
		logger.info(exception.message, exception)
		return ResponseEntity.status(status).body(ErrorResponseDto(exception.message, getCorrelationId()))
	}

	@ExceptionHandler
	fun handleGenericException(exception: Exception): ResponseEntity<ErrorResponseDto> {
		val responseErrorMessage = if (exception.hasCause(ClientAbortException::class.java)) {
			"Request aborted by client".also { logger.info("$it: ${exception.message} (stackTrace ${exception.stackTrace})") }
		} else {
			"Something went wrong".also { logger.error("$it: ${exception.message}", exception) }
		}
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponseDto(responseErrorMessage, getCorrelationId()))
	}

	private fun getCorrelationId(): String = MDC.get(MdcInterceptor.CORRELATION_ID)

}
