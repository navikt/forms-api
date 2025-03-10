package no.nav.forms.config

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Configuration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


@Configuration
class JacksonConfig(
	objectMapper: ObjectMapper,
) {

	init {
		val module = SimpleModule()
		module.addSerializer(OffsetDateTime::class.java, CustomOffsetDateTimeSerializer())
		objectMapper.registerModule(module)
	}

}

class CustomOffsetDateTimeSerializer : JsonSerializer<OffsetDateTime>() {
	private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")

	override fun serialize(value: OffsetDateTime, gen: JsonGenerator, serializers: SerializerProvider) {
		gen.writeString(value.format(formatter))
	}
}
