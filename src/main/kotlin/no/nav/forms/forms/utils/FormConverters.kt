package no.nav.forms.forms.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.forms.forms.repository.entity.*
import no.nav.forms.forms.repository.entity.attributes.FormLockDb
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.model.FormLock
import no.nav.forms.model.FormStatus
import no.nav.forms.utils.mapDateTime
import java.time.LocalDateTime

private val mapper = ObjectMapper()
private val typeRefJsonNodeObject = object : TypeReference<Map<String, Any>>() {}
private val typeRefJsonNodeArray = object : TypeReference<List<Map<String, Any>>>() {}
private val typeRefPublishedLanguages = object : TypeReference<List<String>>() {}

fun FormEntity.findLatestPublication(): FormPublicationEntity? = this.publications.lastOrNull()

fun FormRevisionEntity.toDto(select: List<String>? = null, propLoaders: Map<String, () -> Any?> = emptyMap()): FormDto {
	fun include(prop: String): Boolean = (select == null || select.contains(prop))
	val latestPublication = this.form.findLatestPublication()
	val status = when {
		latestPublication == null -> FormStatus.draft
		latestPublication.status == FormPublicationStatusDb.Unpublished -> FormStatus.unpublished
		this.id == latestPublication.formRevision.id -> FormStatus.published
		else -> FormStatus.pending
	}
	return FormDto(
		id = this.form.id!!,
		revision = if (include("revision")) this.revision else null,
		skjemanummer = if (include("skjemanummer")) this.form.skjemanummer else null,
		path = if (include("path")) this.form.path else null,
		title = if (include("title")) this.title else null,
		properties = if (include("properties")) mapper.convertValue(this.properties, typeRefJsonNodeObject) else null,
		createdAt = if (include("createdAt")) mapDateTime(this.form.createdAt) else null,
		createdBy = if (include("createdBy")) this.form.createdBy else null,
		changedAt = if (include("changedAt")) mapDateTime(this.createdAt) else null,
		changedBy = if (include("changedBy")) this.createdBy else null,
		publishedAt = if (include("publishedAt") && latestPublication != null) mapDateTime(latestPublication.createdAt) else null,
		publishedBy = if (include("publishedBy") && latestPublication != null) latestPublication.createdBy else null,
		publishedLanguages = if (include("publishedLanguages") && latestPublication != null) mapper.convertValue(
			latestPublication.languages,
			typeRefPublishedLanguages
		) else null,
		deletedAt = if (include("deletedAt") && this.form.deletedAt != null) mapDateTime(this.form.deletedAt as LocalDateTime) else null,
		deletedBy = if (include("deletedBy")) this.form.deletedBy else null,
		status = if (include("status")) status else null,
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

fun FormRevisionComponentsEntity.getPropLoader(): () -> Any? = { this.value }
