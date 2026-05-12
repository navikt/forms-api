package no.nav.forms.forms

import no.nav.forms.api.FormPublicationsApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.model.PublishedTranslationsDto
import no.nav.forms.security.SecurityContextHolder
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.hasDuplicates
import no.nav.forms.utils.splitLanguageCodes
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.security.token.support.core.api.Unprotected
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class FormPublicationsController(
	val formPublicationsService: FormPublicationsService,
	private val securityContextHolder: SecurityContextHolder,
) : FormPublicationsApi {

	override fun publishForm(
		formPath: String,
		formsapiEntityRevision: Int,
		languageCodes: String?,
		skipTranslations: Boolean?
	): ResponseEntity<FormDto> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		val languages: List<LanguageCode>? = when {
			skipTranslations == true -> null
			languageCodes?.isNotEmpty() == true -> languageCodes.splitLanguageCodes()
			else -> listOf(LanguageCode.NB)
		}
		val form = formPublicationsService.publishForm(formPath, formsapiEntityRevision, languages, userId)
		return ResponseEntity.status(HttpStatus.CREATED).body(form)
	}

	@Unprotected
	override fun getPublishedForm(formPath: String): ResponseEntity<FormDto> {
		val form = formPublicationsService.getPublishedForm(formPath)
		return ResponseEntity.ok(form)
	}

	@Unprotected
	override fun getPublishedForms(): ResponseEntity<List<FormCompactDto>> {
		val forms = formPublicationsService.getPublishedForms()
		return ResponseEntity.ok(forms)
	}

	@Unprotected
	override fun getPublishedFormTranslations(
		formPath: String,
		languageCodes: String?
	): ResponseEntity<PublishedTranslationsDto> {
		val languages: List<LanguageCode> = languageCodes?.splitLanguageCodes() ?: LanguageCode.entries
		val formTranslations = formPublicationsService.getPublishedFormTranslations(formPath, languages)
		return ResponseEntity.ok(formTranslations)
	}

	override fun unpublishForm(formPath: String): ResponseEntity<Unit> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		formPublicationsService.unpublishForm(formPath, userId)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}


}
