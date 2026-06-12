package no.nav.forms.translations.global

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.nav.forms.model.GlobalTranslationDto
import no.nav.forms.translations.global.repository.GlobalTranslationRepository
import no.nav.forms.translations.global.utils.toDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EditableGlobalTranslationsReadService(
	private val globalTranslationRepository: GlobalTranslationRepository,
	private val meterRegistry: MeterRegistry,
) {

	@Transactional(readOnly = true)
	fun getLatestRevisions(): List<GlobalTranslationDto> {
		val sample = Timer.start(meterRegistry)
		try {
			return globalTranslationRepository.findEditableGlobalTranslations()
				.map { it.toDto() }
		} finally {
			sample.stop(meterRegistry.timer("global_translations_editable_read_seconds"))
		}
	}
}
