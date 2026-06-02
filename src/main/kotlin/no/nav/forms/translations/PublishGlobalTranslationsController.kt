package no.nav.forms.translations

import no.nav.forms.api.PublishGlobalTranslationsApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.PublishedTranslationsDto
import no.nav.forms.security.SecurityContextHolder
import no.nav.forms.translations.global.PublishGlobalTranslationsService
import no.nav.forms.utils.LanguageCode
import no.nav.forms.utils.splitLanguageCodes
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.security.token.support.core.api.Unprotected
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class PublishGlobalTranslationsController(
	private val publishGlobalTranslationsService: PublishGlobalTranslationsService,
	private val securityContextHolder: SecurityContextHolder,
) : PublishGlobalTranslationsApi {

	@Unprotected
	override fun getPublishedGlobalTranslations(languageCode: String): ResponseEntity<Map<String, String>> {
		val lang: LanguageCode = LanguageCode.validate(languageCode)
		return ResponseEntity.ok(publishGlobalTranslationsService.getPublishedGlobalTranslations(lang))
	}

	@Unprotected
	override fun getPublishedGlobalTranslationsInformation(languageCodes: String?): ResponseEntity<PublishedTranslationsDto> {
		val languages: List<LanguageCode>? = languageCodes?.splitLanguageCodes()
		return ResponseEntity.ok(publishGlobalTranslationsService.getPublishedGlobalTranslationsV2(languages))
	}

	override fun publishGlobalTranslations(): ResponseEntity<Unit> {
		securityContextHolder.requireAdminUser()
		val userId = securityContextHolder.getUserName()
		val status = when (publishGlobalTranslationsService.publish(userId)) {
			true -> HttpStatus.CREATED
			else -> HttpStatus.NO_CONTENT
		}
		return ResponseEntity.status(status).build()
	}

}
