package no.nav.forms.translations.global.utils

import no.nav.forms.model.GlobalTranslationDto
import no.nav.forms.translations.global.repository.entity.EditableGlobalTranslationRow
import no.nav.forms.translations.global.repository.entity.GlobalTranslationEntity
import no.nav.forms.translations.global.repository.entity.GlobalTranslationRevisionEntity
import no.nav.forms.translations.global.repository.entity.PublishedGlobalTranslationsEntity
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.mapDateTime

fun GlobalTranslationEntity.toDto(): GlobalTranslationDto {
	val latestRevision = this.getLatestRevision()!!
	val latestPublication = this.getLatestPublication()
	return GlobalTranslationDto(
		id = this.id!!,
		key = this.key,
		tag = this.tag,
		revision = latestRevision.revision,
		nb = latestRevision.nb,
		nn = latestRevision.nn,
		en = latestRevision.en,
		changedAt = mapDateTime(latestRevision.createdAt),
		changedBy = latestRevision.createdBy,
		publishedAt = if (latestPublication != null) mapDateTime(latestPublication.createdAt) else null,
		publishedBy = latestPublication?.createdBy,
	)
}

fun EditableGlobalTranslationRow.toDto(): GlobalTranslationDto {
	return GlobalTranslationDto(
		id = id,
		key = key,
		tag = tag,
		revision = revision,
		nb = nb,
		nn = nn,
		en = en,
		changedAt = mapDateTime(changedAt),
		changedBy = changedBy,
		publishedAt = publishedAt?.let(::mapDateTime),
		publishedBy = publishedBy,
	)
}

fun GlobalTranslationEntity.isDeleted() = this.deletedAt != null

fun GlobalTranslationEntity.getLatestRevision(): GlobalTranslationRevisionEntity? = this.revisions?.lastOrNull()

fun GlobalTranslationEntity.getLatestPublication(): PublishedGlobalTranslationsEntity? {
	val latestPublishedRevision = this.revisions?.lastOrNull { revision -> revision.publications?.isEmpty() == false }
	return latestPublishedRevision?.publications?.last()
}

fun GlobalTranslationRevisionEntity.getTranslation(languageCode: LanguageCode): String? {
	return when (languageCode) {
		LanguageCode.NB -> nb
		LanguageCode.NN -> nn
		LanguageCode.EN -> en
	}
}

fun Set<GlobalTranslationRevisionEntity>.mapToDictionary(languageCode: LanguageCode): Map<String, String> {
	return associate { it.globalTranslation.key to it.getTranslation(languageCode) }
		.filter { it.value != null } as Map<String, String>
}
