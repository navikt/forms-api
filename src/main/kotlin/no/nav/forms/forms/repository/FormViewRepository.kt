package no.nav.forms.forms.repository

import no.nav.forms.commons.repository.ViewRepository
import no.nav.forms.forms.repository.entity.FormPublicationStatusDb
import no.nav.forms.forms.repository.entity.FormViewEntity
import org.springframework.stereotype.Repository

@Repository
interface FormViewRepository: ViewRepository<FormViewEntity, Long> {

	fun findAllByPublicationStatusEquals(publicationStatus: FormPublicationStatusDb): List<FormViewEntity>

	fun findByPath(path: String): FormViewEntity?

	fun findByPathAndDeletedAtIsNull(path: String): FormViewEntity?

	fun findAllByDeletedAtIsNull(): List<FormViewEntity>

}
