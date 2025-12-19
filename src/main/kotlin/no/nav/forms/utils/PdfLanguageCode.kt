package no.nav.forms.utils


enum class PdfLanguageCode(val value: String) {
	NB("nb"),
	NN("nn"),
	EN("en"),
	SA("sa"),
	FR("fr");

	companion object {
		fun forValue(value: String): PdfLanguageCode? = entries.firstOrNull { it.value == value.lowercase() }

		fun validate(lang: String): PdfLanguageCode {
			return forValue(lang) ?: throw IllegalArgumentException("Language code '$lang' is not supported for pdfs")
		}
	}
}
