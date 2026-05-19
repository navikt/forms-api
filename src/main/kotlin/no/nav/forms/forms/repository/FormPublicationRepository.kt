package no.nav.forms.forms.repository

import no.nav.forms.forms.repository.entity.FormPublicationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FormPublicationRepository: JpaRepository<FormPublicationEntity, Long> {

	fun findFirstByFormRevisionFormPathOrderByCreatedAtDescIdDesc(formPath: String): FormPublicationEntity?

	fun findFirstByFormRevisionFormPathAndFormRevisionRevisionOrderByCreatedAtDescIdDesc(
		formPath: String,
		revision: Int,
	): FormPublicationEntity?

	fun findFirstByFormRevisionFormPathAndFormRevisionRevisionAndStatusOrderByCreatedAtDescIdDesc(
		formPath: String,
		revision: Int,
		status: no.nav.forms.forms.repository.entity.FormPublicationStatusDb,
	): FormPublicationEntity?

}
