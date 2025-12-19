package no.nav.forms.staticpdfs

import no.nav.forms.api.StaticPdfApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.StaticPdfDto
import no.nav.forms.security.SecurityContextHolder
import no.nav.forms.utils.PdfLanguageCode
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class StaticPdfController(
	private val securityContextHolder: SecurityContextHolder,
	private val staticPdfService: StaticPdfService,
) : StaticPdfApi {
	private final val logger: Logger = LoggerFactory.getLogger(javaClass)

	override fun getStaticPdfs(
		formPath: String
	): ResponseEntity<List<StaticPdfDto>> {
		logger.info("Get all static pdfs from $formPath")
		return ResponseEntity.ok(
			staticPdfService.getAll(formPath)
				.map(StaticPdfMetadata::toDto)
		)
	}

	override fun uploadStaticPdf(
		formPath: String,
		languageCode: String,
		fileContent: MultipartFile
	): ResponseEntity<StaticPdfDto> {
		logger.info("Upload static pdf for $formPath and language code $languageCode")
		securityContextHolder.requireAdminUser()
		val userId = securityContextHolder.getUserName()
		val language = PdfLanguageCode.validate(languageCode)
		val fileMetadata = staticPdfService.save(fileContent, formPath, language, userId)
		return ResponseEntity
			.status(HttpStatus.CREATED)
			.body(fileMetadata.toDto())
	}

	override fun deleteStaticPdf(
		formPath: String,
		languageCode: String
	): ResponseEntity<Unit> {
		logger.info("Delete static pdf for $formPath and language code $languageCode")
		staticPdfService.delete(formPath, PdfLanguageCode.validate(languageCode))
		return ResponseEntity(HttpStatus.NO_CONTENT)
	}
}
