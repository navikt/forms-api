package no.nav.forms.forms.repository.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import no.nav.forms.forms.repository.entity.FormAttributeName

@Converter(autoApply = true)
class FormAttributeNameConverter : AttributeConverter<FormAttributeName, String?> {
	override fun convertToDatabaseColumn(name: FormAttributeName?): String? = name?.key

	override fun convertToEntityAttribute(dbValue: String?): FormAttributeName? =
		FormAttributeName.entries.firstOrNull { it.key == dbValue }
}
