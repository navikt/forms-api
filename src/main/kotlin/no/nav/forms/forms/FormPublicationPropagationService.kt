package no.nav.forms.forms

import org.springframework.transaction.annotation.Transactional
import no.nav.forms.forms.repository.FormPublicationRepository
import no.nav.forms.forms.repository.FormViewRepository
import no.nav.forms.forms.repository.entity.FormPublicationStatusDb
import no.nav.forms.translations.global.repository.entity.PublishedGlobalTranslationsEntity
import org.springframework.stereotype.Service

@Service
class FormPublicationPropagationService(
	private val formViewRepository: FormViewRepository,
	private val formPublicationRepository: FormPublicationRepository,
) {

	@Transactional
	fun propagateGlobalTranslationPublish(
		newGlobalTranslationPublication: PublishedGlobalTranslationsEntity,
	) {
		val newPublications = formViewRepository
			.findAllByDeletedAtIsNullAndPublicationStatusEquals(FormPublicationStatusDb.Published)
			.map { form ->
				val latestPublication = requireNotNull(
					formPublicationRepository.findFirstByFormRevisionFormPathOrderByCreatedAtDescIdDesc(form.path)
				) {
					"Expected latest publication for published form ${form.path}"
				}
				check(latestPublication.status == FormPublicationStatusDb.Published) {
					"Expected latest publication for ${form.path} to be Published"
				}
				latestPublication.copy(
					publishedGlobalTranslation = newGlobalTranslationPublication,
				)
			}

		formPublicationRepository.saveAll(newPublications)
	}

}
