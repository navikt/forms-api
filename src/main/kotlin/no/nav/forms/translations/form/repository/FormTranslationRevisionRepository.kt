package no.nav.forms.translations.form.repository

import no.nav.forms.translations.form.repository.entity.FormTranslationRevisionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FormTranslationRevisionRepository: JpaRepository<FormTranslationRevisionEntity, Long> {

	fun deleteAllByFormTranslationIdAndRevisionGreaterThan(formTranslationId: Long, revision: Int): Int

}
