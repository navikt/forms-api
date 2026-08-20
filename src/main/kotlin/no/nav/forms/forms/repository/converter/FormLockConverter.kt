package no.nav.forms.forms.repository.converter

import tools.jackson.databind.ObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import no.nav.forms.forms.repository.entity.attributes.FormLockDb

@Converter
class FormLockConverter(
	val mapper: ObjectMapper
): AttributeConverter<FormLockDb, String>{

	override fun convertToDatabaseColumn(lock: FormLockDb?): String? {
		return if (lock != null) mapper.writeValueAsString(lock) else null
	}

	override fun convertToEntityAttribute(dbData: String?): FormLockDb? {
		return if (dbData != null) mapper.readValue(dbData, FormLockDb::class.java) else null
	}
}
