package no.nav.forms.forms

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import no.nav.forms.exceptions.DuplicateResourceException
import no.nav.forms.exceptions.InvalidRevisionException
import no.nav.forms.exceptions.LockedResourceException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.forms.repository.FormAttributeRepository
import no.nav.forms.forms.repository.FormRepository
import no.nav.forms.forms.repository.FormRevisionRepository
import no.nav.forms.forms.repository.FormViewRepository
import no.nav.forms.forms.repository.entity.FormAttributeEntity
import no.nav.forms.forms.repository.entity.FormEntity
import no.nav.forms.forms.repository.entity.FormRevisionEntity
import no.nav.forms.forms.repository.entity.attributes.FormLockDb
import no.nav.forms.forms.utils.getPropLoader
import no.nav.forms.forms.utils.toFormCompactDto
import no.nav.forms.forms.utils.toDto
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
	val formAttributeRepository: FormAttributeRepository,
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
		introPage: Map<String, Any>?,
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

		val componentsEntity = formAttributeRepository.save(
			FormAttributeEntity(name = "components", value = mapper.valueToTree(components))
		)

		val propertiesEntity = formAttributeRepository.save(
			FormAttributeEntity(name = "properties", value = mapper.valueToTree(properties))
		)

		val introPageEntity = introPage?.let {
			formAttributeRepository.save(FormAttributeEntity("introPage", mapper.valueToTree(it)))
		}

		val formRevision = formRevisionRepository.save(
			FormRevisionEntity(
				form = form,
				revision = 1,
				title = title,
				componentsId = componentsEntity.id!!,
				introPageId = introPageEntity?.id,
				properties = propertiesEntity,
				createdAt = now,
				createdBy = userId,
			)
		)

		logger.info("New form created: $skjemanummer")
		return formRevision.toDto(
			propLoaders = mapOf(
				"introPage" to introPageEntity.getPropLoader(),
				"components" to componentsEntity.getPropLoader()
			)
		)
	}

	@Transactional(Transactional.TxType.SUPPORTS)
	fun getForm(formPath: String, listOfProperties: List<String>? = null, includeDeleted: Boolean): FormDto {
		val form = when {
			includeDeleted -> formRepository.findByPath(formPath)
			else -> formRepository.findByPathAndDeletedAtIsNull(formPath)
		} ?: throw ResourceNotFoundException("Form not found", formPath)
		val latestRevision = form.revisions.last()
		val loadIntroPage: () -> JsonNode? = {
			latestRevision.introPageId?.let {
				formAttributeRepository.findById(it)
					.getOrElse { throw IllegalStateException("Failed to load intro page for latest form revision (${formPath})") }.value
			}
		}
		val loadComponents: () -> JsonNode? = {
			formAttributeRepository.findById(latestRevision.componentsId)
				.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }.value
		}
		return latestRevision.toDto(
			listOfProperties,
			mapOf(
				"introPage" to loadIntroPage,
				"components" to loadComponents
			)
		)
	}

	@Transactional
	fun updateForm(
		formPath: String,
		revision: Int,
		title: String? = null,
		components: List<Map<String, Any>>? = null,
		properties: Map<String, Any>? = null,
		introPage: Map<String, Any>? = null,
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
			formAttributeRepository.save(
				FormAttributeEntity(
					name = "components",
					value = mapper.valueToTree(components)
				)
			)
		} else formAttributeRepository.findById(latestFormRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }

		val propertiesEntity = when {
			properties != null -> formAttributeRepository.save(
				FormAttributeEntity(name = "properties", value = mapper.valueToTree(properties))
			)

			else -> latestFormRevision.properties
		}

		val introPageEntity = when {
			introPage != null -> formAttributeRepository.save(FormAttributeEntity("introPage", mapper.valueToTree(introPage)))
			latestFormRevision.introPageId != null -> formAttributeRepository.findById(latestFormRevision.introPageId!!)
				.getOrElse { throw IllegalStateException("Failed to load intro page for latest form revision (${formPath})") }

			else -> null
		}

		val formRevision = formRevisionRepository.save(
			FormRevisionEntity(
				form = form,
				revision = latestFormRevision.revision + 1,
				title = title ?: latestFormRevision.title,
				componentsId = componentsEntity.id!!,
				properties = propertiesEntity,
				introPageId = introPageEntity?.id,
				createdAt = LocalDateTime.now(),
				createdBy = userId,
			)
		)
		return formRevision.toDto(
			propLoaders = mapOf(
				"introPage" to introPageEntity.getPropLoader(),
				"components" to componentsEntity.getPropLoader()
			)
		)
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
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath) ?: throw ResourceNotFoundException(
			"Form not found",
			formPath
		)
		formRepository.setLockOnForm(FormLockDb(OffsetDateTime.now(), userId, lockReason), form.id!!)
		entityManager.refresh(form)
		logger.info("Form ${form.skjemanummer} ($formPath) locked by $userId")

		val latestRevision = form.revisions.last()
		val componentsEntity = formAttributeRepository.findById(latestRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
		val introPageEntity = when {
			latestRevision.introPageId != null -> formAttributeRepository.findById(latestRevision.introPageId!!)
				.getOrElse { throw IllegalStateException("Failed to load intro page for latest form revision (${formPath})") }

			else -> null
		}
		return latestRevision.toDto(
			propLoaders = mapOf(
				"introPage" to introPageEntity.getPropLoader(),
				"components" to componentsEntity.getPropLoader()
			)
		)
	}

	@Transactional
	fun unlockForm(formPath: String, userId: String): FormDto {
		val form = formRepository.findByPathAndDeletedAtIsNull(formPath) ?: throw ResourceNotFoundException(
			"Form not found",
			formPath
		)
		formRepository.setLockOnForm(null, form.id!!)
		entityManager.refresh(form)
		logger.info("Form ${form.skjemanummer} ($formPath) unlocked by $userId")

		val latestRevision = form.revisions.last()
		val componentsEntity = formAttributeRepository.findById(latestRevision.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for latest form revision (${formPath})") }
		val introPageEntity = when {
			latestRevision.introPageId != null -> formAttributeRepository.findById(latestRevision.introPageId!!)
				.getOrElse { throw IllegalStateException("Failed to load intro page for latest form revision (${formPath})") }

			else -> null
		}
		return latestRevision.toDto(
			propLoaders = mapOf(
				"introPage" to introPageEntity.getPropLoader(),
				"components" to componentsEntity.getPropLoader()
			)
		)
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
