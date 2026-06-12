package no.nav.forms.translations.global

import jakarta.persistence.EntityManager
import org.springframework.transaction.annotation.Transactional
import no.nav.forms.exceptions.DuplicateResourceException
import no.nav.forms.exceptions.InvalidRevisionException
import no.nav.forms.exceptions.ResourceDependencyException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.model.GlobalTranslationDto
import no.nav.forms.translations.form.repository.FormTranslationRepository
import no.nav.forms.translations.global.repository.GlobalTranslationRepository
import no.nav.forms.translations.global.repository.GlobalTranslationRevisionRepository
import no.nav.forms.translations.global.repository.entity.GlobalTranslationEntity
import no.nav.forms.translations.global.repository.entity.GlobalTranslationRevisionEntity
import no.nav.forms.translations.global.utils.getLatestRevision
import no.nav.forms.translations.global.utils.isDeleted
import no.nav.forms.translations.global.utils.toDto
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrElse

@Service
class EditGlobalTranslationsService(
	val globalTranslationRevisionRepository: GlobalTranslationRevisionRepository,
	val globalTranslationRepository: GlobalTranslationRepository,
	val formTranslationRepository: FormTranslationRepository,
	val entityManager: EntityManager,
	private val editableGlobalTranslationsReadService: EditableGlobalTranslationsReadService,
) {

	@Transactional(readOnly = true)
	fun getLatestRevisions(): List<GlobalTranslationDto> {
		return editableGlobalTranslationsReadService.getLatestRevisions()
	}

	@Transactional
	fun createGlobalTranslation(
		key: String,
		tag: String,
		nb: String?,
		nn: String?,
		en: String?,
		userId: String
	): GlobalTranslationDto {
		val existingGlobalTranslation = globalTranslationRepository.findByKey(key).also {
			if (it != null && !it.isDeleted())
				throw DuplicateResourceException("Global translation already exists", it.id.toString())
		}
		val globalTranslation = existingGlobalTranslation ?: globalTranslationRepository.save(
			GlobalTranslationEntity(
				key = key,
				tag = tag,
			)
		)
		if (globalTranslation.isDeleted()) {
			globalTranslation.deletedAt = null
			globalTranslation.deletedBy = null
			globalTranslation.tag = tag
			globalTranslationRepository.save(globalTranslation)
		}
		val latestRevision = globalTranslation.getLatestRevision()
		globalTranslationRevisionRepository.save(
			GlobalTranslationRevisionEntity(
				nb = nb,
				nn = nn,
				en = en,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
				globalTranslation = globalTranslation,
				revision = (latestRevision?.revision?.plus(1)) ?: 1,
			)
		)
		entityManager.flush()
		entityManager.refresh(globalTranslation)

		return globalTranslation.toDto()
	}

	@Transactional
	fun updateGlobalTranslation(
		id: Long,
		revision: Int,
		nb: String?,
		nn: String?,
		en: String?,
		tag: String?,
		userId: String
	): GlobalTranslationDto {
		val globalTranslation = globalTranslationRepository.findByIdAndDeletedAtIsNull(id)
			?: throw ResourceNotFoundException("Global translation not found", id.toString())
		val latestRevision = globalTranslation.revisions?.lastOrNull()
		if (latestRevision?.revision != revision) {
			throw InvalidRevisionException("Unexpected global translation revision: $revision")
		}
		if (tag != null) {
			globalTranslation.tag = tag
			globalTranslationRepository.save(globalTranslation)
		}
		globalTranslationRevisionRepository.save(
			GlobalTranslationRevisionEntity(
				nb = nb,
				nn = nn,
				en = en,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
				globalTranslation = globalTranslation,
				revision = revision + 1,
			)
		)
		entityManager.flush()
		entityManager.refresh(globalTranslation)
		return globalTranslation.toDto()
	}

	@Transactional
	fun deleteGlobalTranslation(id: Long, userId: String) {
		val globalTranslation = globalTranslationRepository.findById(id)
			.getOrElse { throw ResourceNotFoundException("Global translation not found", id.toString()) }

		val isCurrentlyReferenced = formTranslationRepository.findAllByRevisionsGlobalTranslationId(globalTranslation.id!!)
			.any { it.revisions?.lastOrNull()?.globalTranslation?.id == id }
		if (isCurrentlyReferenced) {
			throw ResourceDependencyException("Cannot delete global translation since it is referenced by one or more form translations")
		}

		globalTranslation.deletedAt = LocalDateTime.now()
		globalTranslation.deletedBy = userId
		globalTranslationRepository.save(globalTranslation)
	}

}
