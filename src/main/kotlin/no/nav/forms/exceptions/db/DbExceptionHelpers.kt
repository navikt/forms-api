package no.nav.forms.exceptions.db

import org.springframework.http.HttpStatus
import org.springframework.orm.jpa.JpaSystemException

enum class DbError(val id: String, val message: String, val httpStatus: HttpStatus) {
	FORMSAPI_001("DB.FORMSAPI.001", "Do not provide local translations when linked to global translation", HttpStatus.BAD_REQUEST),
	FORMSAPI_002("DB.FORMSAPI.002", "Form cannot be deleted since it has been published", HttpStatus.CONFLICT)
}

val errorRegex = Regex(".*(DB.FORMSAPI.\\d{3}).*")

fun JpaSystemException.getFormsApiDbError(): DbError? {
	return if (message != null) {
		errorRegex.find(message!!)
			.let { DbError.entries.find { err -> err.id == it?.groups?.get(1)?.value } }
	} else null
}
