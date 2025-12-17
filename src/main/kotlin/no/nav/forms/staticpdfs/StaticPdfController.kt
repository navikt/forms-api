package no.nav.forms.staticpdfs

import no.nav.forms.api.StaticPdfApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.StaticPdfDto
import no.nav.forms.security.SecurityContextHolder
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.security.token.support.core.api.Unprotected
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class StaticPdfController(private val securityContextHolder: SecurityContextHolder ): StaticPdfApi{
	private final val logger: Logger = LoggerFactory.getLogger(javaClass)

	@Unprotected
	override fun getStaticPdfs(
		formPath: String
	): ResponseEntity<List<StaticPdfDto>> {
		logger.info("Get all static pdfs from $formPath")
		return ResponseEntity.ok(emptyList())
	}

	@Unprotected
	override fun uploadStaticPdf(
		formPath: String,
		languageCode: String
	): ResponseEntity<Unit> {
		logger.info("Upload static pdf for $formPath and language code $languageCode")
		return ResponseEntity(HttpStatus.OK)
	}

	@Unprotected
	override fun deleteStaticPdf(
		formPath: String,
		languageCode: String
	): ResponseEntity<Unit> {
		logger.info("Delete static pdf for $formPath and language code $languageCode")
		return ResponseEntity(HttpStatus.OK)
	}
}
