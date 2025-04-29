package no.nav.forms.forms

import no.nav.forms.api.EditFormsApi
import no.nav.forms.config.AzureAdConfig
import no.nav.forms.model.FormCompactDto
import no.nav.forms.model.FormDto
import no.nav.forms.model.LockFormRequest
import no.nav.forms.model.NewFormRequest
import no.nav.forms.model.UpdateFormRequest
import no.nav.forms.security.SecurityContextHolder
import no.nav.security.token.support.core.api.ProtectedWithClaims
import no.nav.security.token.support.core.api.Unprotected
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
@ProtectedWithClaims(issuer = AzureAdConfig.ISSUER, claimMap = ["${AzureAdConfig.CLAIM_NAV_IDENT}=*"])
class EditFormsController(
	private val editFormsService: EditFormsService,
	private val securityContextHolder: SecurityContextHolder,
) : EditFormsApi {

	override fun createForm(newFormRequest: NewFormRequest): ResponseEntity<FormDto> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		val newForm = editFormsService.createForm(
			newFormRequest.skjemanummer.trim(),
			newFormRequest.title.trim(),
			newFormRequest.components,
			newFormRequest.properties,
			userId,
		)
		return ResponseEntity.status(HttpStatus.CREATED).body(newForm)
	}

	@Unprotected
	override fun getForm(formPath: String, includeDeleted: Boolean): ResponseEntity<FormDto> {
		val form = editFormsService.getForm(formPath, includeDeleted)
		return ResponseEntity.ok(form)
	}

	override fun updateForm(
		formPath: String,
		formsapiEntityRevision: Int,
		updateFormRequest: UpdateFormRequest
	): ResponseEntity<FormDto> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		val form = editFormsService.updateForm(
			formPath,
			formsapiEntityRevision,
			updateFormRequest.title?.trim(),
			updateFormRequest.components,
			updateFormRequest.properties,
			userId
		)
		return ResponseEntity.ok(form)
	}

	@Unprotected
	override fun getForms(select: String?, includeDeleted: Boolean): ResponseEntity<List<FormCompactDto>> {
		val selectList = when {
			select?.isNotEmpty() == true -> select.split(",")
			else -> null
		}
		val forms = editFormsService.getForms(selectList, includeDeleted)
		return ResponseEntity.ok(forms)
	}

	override fun lockForm(formPath: String, lockFormRequest: LockFormRequest): ResponseEntity<FormDto> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		val form = editFormsService.lockForm(
			formPath,
			lockFormRequest.reason,
			userId,
		)
		return ResponseEntity.ok(form)
	}

	override fun unlockForm(formPath: String): ResponseEntity<FormDto> {
		securityContextHolder.requireValidUser()
		val userId = securityContextHolder.getUserName()
		val form = editFormsService.unlockForm(formPath, userId)
		return ResponseEntity.ok(form)
	}

	override fun deleteForm(formPath: String, formsapiEntityRevision: Int): ResponseEntity<FormDto> {
		securityContextHolder.requireAdminUser()
		val userId = securityContextHolder.getUserName()
		editFormsService.deleteForm(formPath, formsapiEntityRevision, userId)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

}
