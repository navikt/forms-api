package no.nav.forms.forms

import no.nav.forms.api.DatabaseCleanupApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.DatabaseCleanupJob
import no.nav.forms.model.DatabaseCleanupPreview
import no.nav.forms.model.DatabaseCleanupRequest
import no.nav.forms.model.DatabaseCleanupStartRequest
import no.nav.forms.model.DatabaseCleanupStarted
import no.nav.forms.security.SecurityContextHolder
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class DatabaseCleanupController(
    private val service: FormClearService,
    private val security: SecurityContextHolder,
) : DatabaseCleanupApi {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    override fun previewDatabaseCleanup(databaseCleanupRequest: DatabaseCleanupRequest): ResponseEntity<DatabaseCleanupPreview> {
        security.requireAdminUser()
        return ResponseEntity.ok(service.preview(databaseCleanupRequest))
    }

    override fun startDatabaseCleanup(databaseCleanupStartRequest: DatabaseCleanupStartRequest): ResponseEntity<DatabaseCleanupStarted> {
        security.requireAdminUser()
        return ResponseEntity.status(HttpStatus.CREATED).body(
            DatabaseCleanupStarted(service.start(databaseCleanupStartRequest, security.getNavIdent()).toString())
        )
    }

    override fun getActiveDatabaseCleanupJob(): ResponseEntity<DatabaseCleanupJob> {
        security.requireAdminUser()
        return ResponseEntity.ok(service.activeJob())
    }

    override fun getDatabaseCleanupJob(jobId: String): ResponseEntity<DatabaseCleanupJob> {
        security.requireAdminUser()
        return ResponseEntity.ok(service.status(UUID.fromString(jobId)))
    }
}
