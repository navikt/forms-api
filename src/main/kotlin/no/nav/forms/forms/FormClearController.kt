package no.nav.forms.forms

import no.nav.forms.config.AzureAdConfig
import no.nav.forms.security.SecurityContextHolder
import no.nav.security.token.support.core.api.ProtectedWithClaims
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.converter.HttpMessageNotReadableException
import java.util.UUID

data class FormClearRequest(
    val keepTestForms: Boolean,
    val keepLockedForms: Boolean,
    val keepFormPaths: List<String>,
)

data class FormClearStartRequest(
    val keepTestForms: Boolean,
    val keepLockedForms: Boolean,
    val keepFormPaths: List<String>,
    val expectedToDelete: List<String>,
    val expectedKept: List<String>,
) {
    fun keepRequest() = FormClearRequest(keepTestForms, keepLockedForms, keepFormPaths)
}

data class FormClearPreview(val toDelete: List<String>, val kept: List<String>)
data class FormClearStarted(val jobId: UUID)
data class FormClearItem(val path: String, val outcome: String, val error: String? = null)
data class FormClearJob(
    val jobId: UUID,
    val status: String,
    val totalCount: Int,
    val processedCount: Int,
    val deletedCount: Int,
    val keptCount: Int,
    val failedCount: Int,
    val items: List<FormClearItem>,
)

@RestController
@RequestMapping("/api/form-clear")
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class FormClearController(
    private val service: FormClearService,
    private val security: SecurityContextHolder,
) {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedRequest(): ResponseEntity<Void> = ResponseEntity.badRequest().build()

    @PostMapping("/preview")
    fun preview(@RequestBody request: FormClearRequest): FormClearPreview {
        security.requireAdminUser()
        return service.preview(request)
    }

    @PostMapping("/jobs")
    fun start(@RequestBody request: FormClearStartRequest): ResponseEntity<FormClearStarted> {
        security.requireAdminUser()
        return ResponseEntity.status(HttpStatus.CREATED).body(FormClearStarted(service.start(request, security.getNavIdent())))
    }

    @GetMapping("/jobs/active")
    fun activeJob(): FormClearJob {
        security.requireAdminUser()
        return service.activeJob()
    }

    @GetMapping("/jobs/{jobId}")
    fun status(@PathVariable jobId: UUID): FormClearJob {
        security.requireAdminUser()
        return service.status(jobId)
    }
}
