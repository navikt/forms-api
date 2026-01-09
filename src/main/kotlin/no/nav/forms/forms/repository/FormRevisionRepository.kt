package no.nav.forms.forms.repository

import no.nav.forms.forms.repository.entity.FormRevisionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FormRevisionRepository : JpaRepository<FormRevisionEntity, Long> {

	fun deleteAllByFormPathAndRevisionGreaterThan(formPath: String, revision: Int): Int

}
