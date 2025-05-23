package no.nav.forms.forms.repository.converter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class DbJsonbConverter : AttributeConverter<JsonNode, String> {

	private val mapper = ObjectMapper()

	override fun convertToDatabaseColumn(node: JsonNode?): String? {
		if (node == null) return null
		return node.toString()
	}

	override fun convertToEntityAttribute(data: String?): JsonNode? {
		if (data.isNullOrEmpty()) return null
		return mapper.readTree(data)
	}

}
