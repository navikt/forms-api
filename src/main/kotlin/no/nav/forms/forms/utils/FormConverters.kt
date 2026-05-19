package no.nav.forms.forms.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.forms.forms.repository.FormAttributeRepository
import no.nav.forms.forms.repository.entity.*
import no.nav.forms.forms.repository.entity.attributes.FormLockDb
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.model.FormLock
import no.nav.forms.model.FormStatus
import no.nav.forms.utils.mapDateTime
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrElse

private val mapper = ObjectMapper()
private val typeRefJsonNodeObject = object : TypeReference<Map<String, Any>>() {}
private val typeRefJsonNodeArray = object : TypeReference<List<Map<String, Any>>>() {}
private val typeRefPublishedLanguages = object : TypeReference<List<String>>() {}
private val publicationOrder = compareBy<FormPublicationEntity>({ it.createdAt }, { it.id ?: Long.MIN_VALUE })

data class FormPublicationContext(
	val status: FormStatus,
	val publishedAt: LocalDateTime? = null,
	val publishedBy: String? = null,
	val publishedLanguages: List<String>? = null,
)

fun FormEntity.findLatestPublication(): FormPublicationEntity? = this.publications.maxWithOrNull(publicationOrder)

fun FormEntity.toLatestPublicationContext(currentRevision: FormRevisionEntity): FormPublicationContext {
	val latestPublication = this.findLatestPublication()
	val status = when {
		latestPublication == null -> FormStatus.draft
		latestPublication.status == FormPublicationStatusDb.Unpublished -> FormStatus.unpublished
		currentRevision.id == latestPublication.formRevision.id -> FormStatus.published
		else -> FormStatus.pending
	}
	return latestPublication?.toPublicationContext(status) ?: FormPublicationContext(status)
}

fun FormRevisionEntity.toRevisionPublicationContext(
	latestRevisionEvent: FormPublicationEntity?,
	latestPublishedRevisionEvent: FormPublicationEntity?,
): FormPublicationContext {
	val status = when {
		latestRevisionEvent == null -> FormStatus.draft
		latestRevisionEvent.status == FormPublicationStatusDb.Unpublished -> FormStatus.unpublished
		else -> FormStatus.published
	}
	return latestPublishedRevisionEvent?.toPublicationContext(status) ?: FormPublicationContext(status)
}

fun FormPublicationEntity.toPublishedSnapshotContext(): FormPublicationContext = toPublicationContext(FormStatus.published)

private fun FormPublicationEntity.toPublicationContext(status: FormStatus): FormPublicationContext {
	return FormPublicationContext(
		status = status,
		publishedAt = this.createdAt,
		publishedBy = this.createdBy,
		publishedLanguages = mapper.convertValue(this.languages, typeRefPublishedLanguages),
	)
}

fun FormRevisionEntity.getPropLoaders(
	formPath: String,
	formAttributeRepository: FormAttributeRepository,
): Map<String, () -> Any?> {
	val loadIntroPage: () -> JsonNode? = {
		this.introPageId?.let {
			formAttributeRepository.findById(it)
				.getOrElse { throw IllegalStateException("Failed to load intro page for form revision (${formPath})") }.value
		}
	}
	val loadComponents: () -> JsonNode = {
		formAttributeRepository.findById(this.componentsId)
			.getOrElse { throw IllegalStateException("Failed to load components for form revision (${formPath})") }.value
	}
	return mapOf(
		"introPage" to loadIntroPage,
		"components" to loadComponents,
	)
}

fun FormRevisionEntity.toDto(
	select: List<String>? = null,
	propLoaders: Map<String, () -> Any?> = emptyMap(),
	publicationContext: FormPublicationContext,
): FormDto {
	fun include(prop: String): Boolean = (select == null || select.contains(prop))
	return FormDto(
		id = this.form.id!!,
		revision = if (include("revision")) this.revision else null,
		skjemanummer = if (include("skjemanummer")) this.form.skjemanummer else null,
		path = if (include("path")) this.form.path else null,
		title = if (include("title")) this.title else null,
		properties = if (include("properties")) mapper.convertValue(this.properties.value, typeRefJsonNodeObject) else null,
		createdAt = if (include("createdAt")) mapDateTime(this.form.createdAt) else null,
		createdBy = if (include("createdBy")) this.form.createdBy else null,
		changedAt = if (include("changedAt")) mapDateTime(this.createdAt) else null,
		changedBy = if (include("changedBy")) this.createdBy else null,
		publishedAt = if (include("publishedAt") && publicationContext.publishedAt != null) mapDateTime(publicationContext.publishedAt) else null,
		publishedBy = if (include("publishedBy")) publicationContext.publishedBy else null,
		publishedLanguages = if (include("publishedLanguages")) publicationContext.publishedLanguages else null,
		deletedAt = if (include("deletedAt") && this.form.deletedAt != null) mapDateTime(this.form.deletedAt as LocalDateTime) else null,
		deletedBy = if (include("deletedBy")) this.form.deletedBy else null,
		status = if (include("status")) publicationContext.status else null,
		lock = if (include("lock")) this.form.lock?.toFormLockDto() else null,
		introPage = if (include("introPage")) propLoaders["introPage"]?.invoke()
			?.let { mapper.convertValue(it, typeRefJsonNodeObject) } else null,
		components = if (include("components")) propLoaders["components"]?.invoke()
			?.let { mapper.convertValue(it, typeRefJsonNodeArray) } else null,
	)
}

fun FormLockDb.toFormLockDto(): FormLock? {
	return FormLock(
		this.createdAt,
		this.createdBy,
		this.reason,
	)
}

fun FormViewEntity.toFormCompactDto(select: List<String>? = null): FormCompactDto {
	fun include(prop: String): Boolean = (select == null || select.contains(prop))
	val status = when {
		this.publicationStatus == null -> FormStatus.draft
		this.publicationStatus == FormPublicationStatusDb.Unpublished -> FormStatus.unpublished
		this.currentRevisionId == this.publishedRevisionId -> FormStatus.published
		this.publishedRevisionId != null -> FormStatus.pending
		else -> null
	}
	return FormCompactDto(
		id = this.id,
		revision = if (include("revision")) this.revision else null,
		skjemanummer = if (include("skjemanummer")) this.skjemanummer else null,
		path = if (include("path")) this.path else null,
		title = if (include("title")) this.title else null,
		properties = if (include("properties")) mapper.convertValue(this.properties, typeRefJsonNodeObject) else null,
		changedAt = if (include("changedAt")) mapDateTime(this.changedAt) else null,
		changedBy = if (include("changedBy")) this.changedBy else null,
		publishedAt = if (include("publishedAt") && this.publishedAt != null) mapDateTime(this.publishedAt as LocalDateTime) else null,
		publishedBy = if (include("publishedBy")) this.publishedBy else null,
		deletedAt = if (include("deletedAt") && this.deletedAt != null) mapDateTime(this.deletedAt as LocalDateTime) else null,
		deletedBy = if (include("deletedBy")) this.deletedBy else null,
		status = if (include("status")) status else null,
		lock = if (include("lock")) this.lock?.toFormLockDto() else null,
	)
}

fun FormAttributeEntity?.getPropLoader(): () -> Any? = { this?.value }
