package no.nav.forms.forms

import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import no.nav.forms.exceptions.InvalidRevisionException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.forms.repository.FormPublicationRepository
import no.nav.forms.forms.repository.FormRepository
import no.nav.forms.forms.repository.FormRevisionComponentsRepository
import no.nav.forms.forms.repository.FormViewRepository
import no.nav.forms.forms.repository.entity.FormPublicationEntity
import no.nav.forms.forms.repository.entity.FormPublicationStatusDb
import no.nav.forms.forms.utils.findLatestPublication
import no.nav.forms.forms.utils.toDto
import no.nav.forms.forms.utils.toFormCompactDto
import no.nav.forms.forms.utils.withComponents
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.model.PublishedTranslationsDto
import no.nav.forms.translations.form.repository.FormTranslationRepository
import no.nav.forms.translations.form.repository.PublishedFormTranslationRepository
import no.nav.forms.translations.form.repository.entity.PublishedFormTranslationsEntity
import no.nav.forms.translations.form.utils.mapToDictionary
import no.nav.forms.translations.global.repository.PublishedGlobalTranslationsRepository
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.mapDateTime
import no.nav.forms.utils.toJsonNode
import no.nav.forms.utils.toLanguageCodes
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class FormPublicationsService(
	val formPublicationRepository: FormPublicationRepository,
	val formRepository: FormRepository,
	val formRevisionComponentsRepository: FormRevisionComponentsRepository,
	val publishedGlobalTranslationsRepository: PublishedGlobalTranslationsRepository,
	val publishedFormTranslationRepository: PublishedFormTranslationRepository,
	val formTranslationRepository: FormTranslationRepository,
	val formViewRepository: FormViewRepository,
	val entityManager: EntityManager,
) {

	@Transactional
	fun publishForm(formPath: String, formRevision: Int, languages: List<LanguageCode>?, userId: String): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath) ?: throw ResourceNotFoundException("Form not found", formPath)

		val latestPublicationOfGlobalTranslations = publishedGlobalTranslationsRepository.findFirstByOrderByCreatedAtDesc()
			?: throw ResourceNotFoundException("Publication of global translations not found", "latest")

		val latestFormRevision = form.revisions.last()
		if (latestFormRevision.revision != formRevision) {
			throw InvalidRevisionException("Form revision is not the latest: $formRevision")
		}

		val createdAt = LocalDateTime.now()
		val (publishedFormTranslations, publishedLanguages) = if (languages == null) {
			val formPublication = formPublicationRepository.findFirstByFormRevisionFormPathOrderByCreatedAtDesc(formPath)
				?: throw IllegalArgumentException("Found no existing publication of this form, languages must therefore be provided on publish")
			Pair(formPublication.publishedFormTranslation, formPublication.languages)
		} else {
			val latestFormTranslations = formTranslationRepository.findAllByFormPathAndDeletedAtIsNull(formPath)
				.map { it.revisions!!.last() }
				.filter { it.globalTranslation == null }

			val publishedFormTranslations = publishedFormTranslationRepository.save(
				PublishedFormTranslationsEntity(
					createdAt = createdAt,
					createdBy = userId,
					form = form,
					formTranslationRevisions = latestFormTranslations.toSet()
				)
			)
			Pair(publishedFormTranslations, languages.toJsonNode())
		}

		formPublicationRepository.save(
			FormPublicationEntity(
				createdAt = createdAt,
				createdBy = userId,
				form = form,
				formRevision = latestFormRevision,
				publishedFormTranslation = publishedFormTranslations,
				publishedGlobalTranslation = latestPublicationOfGlobalTranslations,
				languages = publishedLanguages,
				status = FormPublicationStatusDb.Published,
			)
		)
		entityManager.refresh(form)

		val formRevisionComponents = formRevisionComponentsRepository.findById(latestFormRevision.componentsId).get()
		return latestFormRevision.toDto().withComponents(formRevisionComponents)
	}

	@Transactional
	fun getPublishedForm(formPath: String): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath)
			?: throw ResourceNotFoundException("Form not found", formPath)
		val latestPublication = form.findLatestPublication().takeIf { it?.status == FormPublicationStatusDb.Published }
			?: throw ResourceNotFoundException("Form not published", formPath)
		val publishedFormRevision = latestPublication.formRevision
		val formRevisionComponents = formRevisionComponentsRepository.findById(publishedFormRevision.componentsId).get()
		return publishedFormRevision.toDto().withComponents(formRevisionComponents)
	}

	@Transactional
	fun getPublishedForms(): List<FormCompactDto> {
		return formViewRepository
			.findAllByPublicationStatusEquals(FormPublicationStatusDb.Published)
			.map { it.toFormCompactDto() }
	}

	@Transactional
	fun getPublishedFormTranslations(
		formPath: String,
		requestedLanguageCodes: List<LanguageCode>
	): PublishedTranslationsDto {
		val publication = formPublicationRepository.findFirstByFormRevisionFormPathOrderByCreatedAtDesc(formPath)
			?: throw ResourceNotFoundException("Form not published", formPath)
		val publishedLanguages = publication.languages.toLanguageCodes()
		val translations = requestedLanguageCodes.intersect(publishedLanguages).associate {
			it.value to publication.publishedFormTranslation.formTranslationRevisions.mapToDictionary(it)
		}
		return PublishedTranslationsDto(
			publishedAt = mapDateTime(publication.createdAt),
			publishedBy = publication.createdBy,
			translations = translations
		)
	}

	@Transactional
	fun unpublishForm(formPath: String, userId: String) {
		val publication = formPublicationRepository.findFirstByFormRevisionFormPathOrderByCreatedAtDesc(formPath)
			?: throw ResourceNotFoundException("Form not published", formPath)
		if (publication.status == FormPublicationStatusDb.Unpublished) {
			throw IllegalArgumentException("Form $formPath is already unpublished")
		}
		formPublicationRepository.save(
			publication.copy(
				createdAt = LocalDateTime.now(),
				createdBy = userId,
				status = FormPublicationStatusDb.Unpublished,
			)
		)

	}

}
