package no.nav.forms.testutils

import no.nav.forms.model.NewFormRequest
import no.nav.forms.model.UpdateFormRequest
import no.nav.forms.utils.Skjemanummer

class FormsTestdata {

	companion object {
		fun newFormRequest(
			skjemanummer: Skjemanummer = "NAV 12-34.56",
			title: String = "Mitt testskjema",
			properties: Map<String, Any> = mapOf("tema" to "BIL", "innsending" to "PAPIR_OG_DIGITAL"),
			components: List<Map<String, Any>> = listOf(mapOf("type" to "panel")),
			introPage: Map<String, Any>? = null,
		): NewFormRequest {
			return NewFormRequest(
				skjemanummer = skjemanummer,
				title = title,
				components = components,
				properties = properties,
				introPage = introPage,
			)
		}

		fun updateFormRequest(
			title: String? = "Mitt testskjema",
			properties: Map<String, Any>? = mapOf("tema" to "BIL", "innsending" to "PAPIR_OG_DIGITAL"),
			components: List<Map<String, Any>>? = listOf(mapOf("type" to "panel")),
			introPage: Map<String, Any>? = null,
		): UpdateFormRequest {
			return UpdateFormRequest(
				title = title,
				components = components,
				properties = properties,
				introPage = introPage,
			)
		}
	}

}
