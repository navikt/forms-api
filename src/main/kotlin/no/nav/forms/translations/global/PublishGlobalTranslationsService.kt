package no.nav.forms.translations.global

import jakarta.transaction.Transactional
import no.nav.forms.forms.FormPublicationPropagationService
import no.nav.forms.model.PublishedTranslationsDto
import no.nav.forms.translations.global.repository.GlobalTranslationRepository
import no.nav.forms.translations.global.repository.PublishedGlobalTranslationsRepository
import no.nav.forms.translations.global.repository.entity.GlobalTranslationEntity
import no.nav.forms.translations.global.repository.entity.GlobalTranslationRevisionEntity
import no.nav.forms.translations.global.repository.entity.PublishedGlobalTranslationsEntity
import no.nav.forms.translations.global.utils.getLatestRevision
import no.nav.forms.translations.global.utils.mapToDictionary
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.mapDateTime
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class PublishGlobalTranslationsService(
	private val publishedGlobalTranslationsRepository: PublishedGlobalTranslationsRepository,
	private val globalTranslationRepository: GlobalTranslationRepository,
	private val formPublicationPropagationService: FormPublicationPropagationService,
) {

	@Transactional
	fun getPublishedGlobalTranslations(languageCode: LanguageCode): Map<String, String> {
		val publishedGlobalTranslations = publishedGlobalTranslationsRepository.findFirstByOrderByCreatedAtDesc()
			?: throw Exception("No published global translations found")
		return publishedGlobalTranslations.globalTranslationRevisions.mapToDictionary(languageCode)
	}

	@Transactional
	fun publish(userId: String): Boolean {
		val globalTranslations = globalTranslationRepository.findAllByDeletedAtIsNull()
		val latestRevisions = globalTranslations.mapNotNull(GlobalTranslationEntity::getLatestRevision).toSet()
		val latestPublishedGlobalTranslations = publishedGlobalTranslationsRepository.findFirstByOrderByCreatedAtDesc()
		if (latestPublishedGlobalTranslations?.revisionIds() == latestRevisions.revisionIds()) {
			return false
		}

		val savedPublication = publishedGlobalTranslationsRepository.save(
			PublishedGlobalTranslationsEntity(
				createdAt = LocalDateTime.now(),
				createdBy = userId,
				globalTranslationRevisions = latestRevisions
			)
		)
		formPublicationPropagationService.propagateGlobalTranslationPublish(savedPublication)
		return true
	}

	@Transactional
	fun getPublishedGlobalTranslationsV2(languageCodes: List<LanguageCode>?): PublishedTranslationsDto {
		val publishedGlobalTranslations = publishedGlobalTranslationsRepository.findFirstByOrderByCreatedAtDesc()
			?: throw Exception("No published global translations found")
		val translations = languageCodes?.associate {
			it.value to publishedGlobalTranslations.globalTranslationRevisions.mapToDictionary(it)
		}
		return PublishedTranslationsDto(
			publishedAt = mapDateTime(publishedGlobalTranslations.createdAt),
			publishedBy = publishedGlobalTranslations.createdBy,
			translations = translations
		)
	}

}

private fun PublishedGlobalTranslationsEntity.revisionIds(): Set<Long> = globalTranslationRevisions.revisionIds()

private fun Set<GlobalTranslationRevisionEntity>.revisionIds(): Set<Long> = mapNotNull(GlobalTranslationRevisionEntity::id).toSet()
