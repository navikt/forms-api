package no.nav.forms.translations.form.utils

import no.nav.forms.model.FormTranslationDto
import no.nav.forms.translations.form.repository.entity.FormTranslationEntity
import no.nav.forms.translations.form.repository.entity.FormTranslationRevisionEntity
import no.nav.forms.translations.global.utils.getLatestRevision
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.mapDateTime

fun FormTranslationEntity.toDto(): FormTranslationDto {
	val latestRevision = this.revisions?.lastOrNull()
	return FormTranslationDto(
		id = this.id!!,
		key = this.key,
		tag = this.tag,
		revision = latestRevision?.revision,
		nb = latestRevision?.nb,
		nn = latestRevision?.nn,
		en = latestRevision?.en,
		changedAt = latestRevision?.run { mapDateTime(this.createdAt) },
		changedBy = latestRevision?.createdBy,
	)
}

fun FormTranslationRevisionEntity.toDto(): FormTranslationDto {
	val latestGlobalRevision = this.globalTranslation?.getLatestRevision()
	return FormTranslationDto(
		id = this.formTranslation.id!!,
		key = this.formTranslation.key,
		tag = this.formTranslation.tag,
		revision = this.revision,
		globalTranslationId = this.globalTranslation?.id,
		nb = if (latestGlobalRevision != null) latestGlobalRevision.nb else this.nb,
		nn = if (latestGlobalRevision != null) latestGlobalRevision.nn else this.nn,
		en = if (latestGlobalRevision != null) latestGlobalRevision.en else this.en,
		changedAt = mapDateTime(this.createdAt),
		changedBy = this.createdBy,
	)
}

fun FormTranslationRevisionEntity.getTranslation(languageCode: LanguageCode): String? {
	return when (languageCode) {
		LanguageCode.NB -> nb
		LanguageCode.NN -> nn
		LanguageCode.EN -> en
	}
}

fun Set<FormTranslationRevisionEntity>.mapToDictionary(languageCode: LanguageCode): Map<String, String> {
	return associate { it.formTranslation.key to it.getTranslation(languageCode) }
		.filter { it.value != null } as Map<String, String>
}
