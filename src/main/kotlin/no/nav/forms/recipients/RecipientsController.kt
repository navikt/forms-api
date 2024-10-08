package no.nav.forms.recipients

import no.nav.forms.api.RecipientsApi
import no.nav.forms.model.NewRecipientRequest
import no.nav.forms.model.RecipientDto
import no.nav.forms.model.UpdateRecipientRequest
import no.nav.forms.security.TokenHandler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class RecipientsController(
	private val recipientsService: RecipientsService,
	private val tokenHandler: TokenHandler,
) : RecipientsApi {

	override fun getRecipients(): ResponseEntity<List<RecipientDto>> {
		return ResponseEntity.ok(recipientsService.getRecipients())
	}

	override fun getRecipient(recipientId: String): ResponseEntity<RecipientDto> {
		return ResponseEntity.ok(recipientsService.getRecipient(recipientId))
	}

	override fun createRecipient(newRecipientRequest: NewRecipientRequest): ResponseEntity<RecipientDto> {
		val userId = tokenHandler.getUserIdFromToken()
		val dto = recipientsService.createRecipient(
			newRecipientRequest.recipientId,
			newRecipientRequest.name,
			newRecipientRequest.poBoxAddress,
			newRecipientRequest.postalCode,
			newRecipientRequest.postalName,
			userId,
		)
		return ResponseEntity.status(HttpStatus.CREATED).body(dto)
	}

	override fun updateRecipient(recipientId: String, updateRecipientRequest: UpdateRecipientRequest): ResponseEntity<RecipientDto> {
		val userId = tokenHandler.getUserIdFromToken()
		val dto = recipientsService.updateRecipient(
			recipientId,
			updateRecipientRequest.name,
			updateRecipientRequest.poBoxAddress,
			updateRecipientRequest.postalCode,
			updateRecipientRequest.postalName,
			userId,
		)
		return ResponseEntity.ok(dto)
	}

	override fun deleteRecipient(recipientId: String): ResponseEntity<Unit> {
		recipientsService.deleteRecipient(recipientId)
		return ResponseEntity.noContent().build()
	}

}
