package no.nav.forms.forms

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import no.nav.forms.exceptions.DuplicateResourceException
import no.nav.forms.exceptions.InvalidRevisionException
import no.nav.forms.exceptions.LockedResourceException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.forms.repository.FormRepository
import no.nav.forms.forms.repository.FormRevisionComponentsRepository
import no.nav.forms.forms.repository.FormRevisionRepository
import no.nav.forms.forms.repository.FormViewRepository
import no.nav.forms.forms.repository.entity.FormEntity
import no.nav.forms.forms.repository.entity.FormRevisionComponentsEntity
import no.nav.forms.forms.repository.entity.FormRevisionEntity
import no.nav.forms.forms.repository.entity.attributes.FormLockDb
import no.nav.forms.forms.utils.toFormCompactDto
import no.nav.forms.forms.utils.toDto
import no.nav.forms.forms.utils.withComponents
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.utils.Skjemanummer
import no.nav.forms.utils.toFormPath
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.jvm.optionals.getOrElse

@Service
class EditFormsService(
	val formRepository: FormRepository,
	val formRevisionRepository: FormRevisionRepository,
	val formRevisionComponentsRepository: FormRevisionComponentsRepository,
	val formViewRepository: FormViewRepository,
	val entityManager: EntityManager,
) {
	val logger: Logger = LoggerFactory.getLogger(javaClass)
	private val mapper = ObjectMapper()

	@Transactional
	fun createForm(
		skjemanummer: Skjemanummer,
		title: String,
		components: List<Map<String, Any>>,
		properties: Map<String, Any>,
		userId: String,
	): FormDto {
		val formPath = skjemanummer.toFormPath()
		if (formRepository.existsByPath(formPath)) {
			throw DuplicateResourceException("Form $skjemanummer already exists", skjemanummer)
		}
		val now = LocalDateTime.now()
		val form = formRepository.save(
			FormEntity(
				skjemanummer = skjemanummer,
				path = formPath,
				createdAt = now,
				createdBy = userId,
			)
		)

		val componentsEntity = formRevisionComponentsRepository.save(
			FormRevisionComponentsEntity(value = mapper.valueToTree(components))
		)

		val formRevision = formRevisionRepository.save(
			FormRevisionEntity(
				form = form,
				revision = 1,
				title = title,
				componentsId = componentsEntity.id!!,
				properties = mapper.valueToTree(properties),
				createdAt = now,
				createdBy = userId,
			)
		)

		logger.info("New form created: $skjemanummer")
		return formRevision.toDto().withComponents(componentsEntity)
	}

	@Transactional(Transactional.TxType.SUPPORTS)
	fun getForm(formPath: String, listOfProperties: List<String>? = null, includeDeleted: Boolean): FormDto {
		val form = when {
			includeDeleted -> formRepository.findByPath(formPath)
			else -> formRepository.findByPathAndDeletedAtIsNull(formPath)
		} ?: throw ResourceNotFoundException("Form not found", formPath)
		val latestRevision = form.revisions.last()
		return latestRevision.toDto(listOfProperties).also {
			if (listOfProperties == null || listOfProperties.contains("components")) {
				val componentsEntity = formRevisionComponentsRepository.findById(latestRevision.componentsId)
					.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
				return it.withComponents(componentsEntity)
			}
		}
	}

	@Transactional
	fun updateForm(
		formPath: String,
		revision: Int,
		title: String? = null,
		components: List<Map<String, Any>>? = null,
		properties: Map<String, Any>? = null,
		userId: String
	): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath).also {
			if (it?.lock != null) {
				throw LockedResourceException("Form ${it.skjemanummer} is locked: ${it.lock?.reason}")
			}
		} ?: throw ResourceNotFoundException("Form not found", formPath)
		val latestFormRevision = form.revisions.last()
		if (latestFormRevision.revision != revision) {
			throw InvalidRevisionException("Unexpected form revision: $revision")
		}
		val componentsEntity = if (components != null) {
			formRevisionComponentsRepository.save(
				FormRevisionComponentsEntity(
					value = mapper.valueToTree(components)
				)
			)
		} else formRevisionComponentsRepository.findById(latestFormRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
		val formRevision = formRevisionRepository.save(
			FormRevisionEntity(
				form = form,
				revision = latestFormRevision.revision + 1,
				title = title ?: latestFormRevision.title,
				componentsId = componentsEntity.id!!,
				properties = if (properties != null) mapper.valueToTree(properties) else latestFormRevision.properties,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
			)
		)
		return formRevision.toDto().withComponents(componentsEntity)
	}

	@Transactional(Transactional.TxType.SUPPORTS)
	fun getForms(listOfProperties: List<String>? = null, includeDeleted: Boolean): List<FormCompactDto> {
		val forms = when {
			includeDeleted -> formViewRepository.findAll()
			else -> formViewRepository.findAllByDeletedAtIsNull()
		}
		return forms.map { it.toFormCompactDto(listOfProperties) }
	}

	@Transactional
	fun lockForm(formPath: String, lockReason: String, userId: String): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath) ?: throw ResourceNotFoundException("Form not found", formPath)
		formRepository.setLockOnForm(FormLockDb(OffsetDateTime.now(), userId, lockReason), form.id!!)
		entityManager.refresh(form)
		logger.info("Form ${form.skjemanummer} ($formPath) locked by $userId")

		val latestRevision = form.revisions.last()
		val componentsEntity = formRevisionComponentsRepository.findById(latestRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
		return latestRevision.toDto().withComponents(componentsEntity)
	}

	@Transactional
	fun unlockForm(formPath: String, userId: String): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath) ?: throw ResourceNotFoundException("Form not found", formPath)
		formRepository.setLockOnForm(null, form.id!!)
		entityManager.refresh(form)
		logger.info("Form ${form.skjemanummer} ($formPath) unlocked by $userId")

		val latestRevision = form.revisions.last()
		val componentsEntity = formRevisionComponentsRepository.findById(latestRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
		return latestRevision.toDto().withComponents(componentsEntity)
	}

	@Transactional
	fun deleteForm(formPath: String, revision: Int, userId: String) {
		val form = formViewRepository.findByPathAndDeletedAtIsNull(formPath)
			?: throw ResourceNotFoundException("Form not found", formPath)
		if (form.revision != revision) {
			throw InvalidRevisionException("Unexpected form revision: $revision")
		}
		formRepository.deleteForm(LocalDateTime.now(), userId, formPath)
	}
}
