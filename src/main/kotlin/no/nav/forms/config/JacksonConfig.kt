package no.nav.forms.config

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


@Configuration
class JacksonConfig {
	@Bean
	fun jsonMapperBuilderCustomizer() = JsonMapperBuilderCustomizer { builder ->
		val module = SimpleModule()
		module.addSerializer(OffsetDateTime::class.java, CustomOffsetDateTimeSerializer())
		builder.addModule(module)
	}
}

class CustomOffsetDateTimeSerializer : ValueSerializer<OffsetDateTime>() {
	private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")

	override fun serialize(value: OffsetDateTime, gen: JsonGenerator, context: SerializationContext) {
		gen.writeString(value.format(formatter))
	}
}
