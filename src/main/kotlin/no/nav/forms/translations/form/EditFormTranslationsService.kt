package no.nav.forms.translations.form

import jakarta.transaction.Transactional
import no.nav.forms.exceptions.DuplicateResourceException
import no.nav.forms.exceptions.InvalidRevisionException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.forms.repository.FormRepository
import no.nav.forms.model.FormTranslationDto
import no.nav.forms.translations.form.repository.FormTranslationRepository
import no.nav.forms.translations.form.repository.FormTranslationRevisionRepository
import no.nav.forms.translations.form.repository.entity.FormTranslationEntity
import no.nav.forms.translations.form.repository.entity.FormTranslationRevisionEntity
import no.nav.forms.translations.form.utils.toDto
import no.nav.forms.translations.global.repository.GlobalTranslationRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrElse

@Service
class EditFormTranslationsService(
	val formRepository: FormRepository,
	val formTranslationRepository: FormTranslationRepository,
	val formTranslationRevisionRepository: FormTranslationRevisionRepository,
	val globalTranslationRepository: GlobalTranslationRepository,
) {

	@Transactional
	fun getTranslations(formPath: String): List<FormTranslationDto> {
		val translationRevisions = formTranslationRepository.findAllByFormPathAndDeletedAtIsNull(formPath)
			.map { it.revisions!!.last() }
		return translationRevisions.map(FormTranslationRevisionEntity::toDto)
	}

	@Transactional
	fun updateTranslation(
		formPath: String,
		id: Long,
		revision: Int,
		globalTranslationId: Long?,
		nb: String?,
		nn: String?,
		en: String?,
		userId: String,
	): FormTranslationDto {
		val globalTranslation = if (globalTranslationId != null) {
			globalTranslationRepository.findById(globalTranslationId)
				.getOrElse { throw IllegalArgumentException("Global translation not found") }
		} else null

		val formTranslation = formTranslationRepository.findById(id)
			.getOrElse { throw ResourceNotFoundException("Form translation not found", id.toString()) }
		if (formTranslation.form.path != formPath) {
			throw IllegalArgumentException("Illegal combination of form path and form translation id")
		}
		val latestRevision = formTranslation.revisions?.lastOrNull()
		if (latestRevision?.revision != revision) {
			throw InvalidRevisionException("Unexpected form translation revision: $revision")
		}
		val newFormTranslationRevision = formTranslationRevisionRepository.save(
			FormTranslationRevisionEntity(
				formTranslation = formTranslation,
				revision = revision + 1,
				globalTranslation = globalTranslation,
				nb = nb,
				nn = nn,
				en = en,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
			)
		)
		return newFormTranslationRevision.toDto()
	}

	@Transactional
	fun createTranslation(
		formPath: String,
		key: String,
		globalTranslationId: Long?,
		nb: String?,
		nn: String?,
		en: String?,
		userId: String
	): FormTranslationDto {
		val form = formRepository.findByPath(formPath) ?: throw ResourceNotFoundException("Form not found", formPath)

		val globalTranslation = if (globalTranslationId != null) {
			globalTranslationRepository.findById(globalTranslationId)
				.getOrElse { throw IllegalArgumentException("Global translation not found") }
		} else null

		val formTranslation =
			formTranslationRepository.findByFormPathAndKey(formPath, key).let {
				when {
					it == null -> null
					it.deletedAt != null -> formTranslationRepository.save(it.copy(deletedAt = null, deletedBy = null))
					else -> throw DuplicateResourceException(
						"Translation with key is already associated with $formPath",
						formPath
					)
				}
			} ?: formTranslationRepository.save(FormTranslationEntity(form = form, key = key))

		val latestRevision = formTranslation.revisions?.lastOrNull()
		val formTranslationRevision = formTranslationRevisionRepository.save(
			FormTranslationRevisionEntity(
				revision = latestRevision?.revision?.plus(1) ?: 1,
				formTranslation = formTranslation,
				globalTranslation = globalTranslation,
				nb = nb,
				nn = nn,
				en = en,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
			)
		)
		return formTranslationRevision.toDto()
	}

	@Transactional
	fun deleteFormTranslation(formPath: String, id: Long, userId: String) {
		val formTranslation =
			formTranslationRepository.findByFormPathAndId(formPath, id) ?: throw ResourceNotFoundException(
				"Form translation not found",
				id.toString()
			)
		formTranslationRepository.save(
			formTranslation.copy(deletedAt = LocalDateTime.now(), deletedBy = userId)
		);
	}

}
