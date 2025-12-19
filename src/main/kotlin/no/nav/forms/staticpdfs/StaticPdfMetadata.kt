package no.nav.forms.staticpdfs

import no.nav.forms.model.StaticPdfDto
import no.nav.forms.utils.PdfLanguageCode
import java.time.OffsetDateTime

data class StaticPdfMetadata(
	val fileName: String,
	val fileSize: String,
	val language: PdfLanguageCode,
	val createdBy: String,
	val createdAt: OffsetDateTime,
	val formPath: String,
)

fun StaticPdfMetadata.toDto(): StaticPdfDto {
	return StaticPdfDto(
		fileName = this.fileName,
		languageCode = this.language.value,
		createdBy = this.createdBy,
		createdAt = this.createdAt,
	)
}
