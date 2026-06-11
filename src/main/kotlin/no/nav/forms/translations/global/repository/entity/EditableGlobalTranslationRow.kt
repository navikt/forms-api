package no.nav.forms.translations.global.repository.entity

import java.time.LocalDateTime

interface EditableGlobalTranslationRow {
	val id: Long
	val key: String
	val tag: String
	val revision: Int
	val nb: String?
	val nn: String?
	val en: String?
	val changedAt: LocalDateTime
	val changedBy: String
	val publishedAt: LocalDateTime?
	val publishedBy: String?
}
