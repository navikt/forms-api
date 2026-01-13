package no.nav.forms.translations.form.repository

import no.nav.forms.translations.form.repository.entity.FormTranslationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface FormTranslationRepository: JpaRepository<FormTranslationEntity, Long> {

	fun findAllByRevisionsGlobalTranslationId(globalTranslationId: Long): List<FormTranslationEntity>

	fun findByFormPathAndKey(formPath: String, key: String): FormTranslationEntity?

	fun findByFormPathAndId(formPath: String, id: Long): FormTranslationEntity?

	fun findAllByFormPath(formPath: String): List<FormTranslationEntity>

	fun findAllByFormPathAndDeletedAtIsNull(formPath: String): List<FormTranslationEntity>

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(
		"UPDATE FormTranslationEntity f SET f.deletedAt = :deletedAt, f.deletedBy = :deletedBy " +
			"WHERE f.form.path = :formPath AND f.deletedAt IS NULL AND EXISTS (" +
			"SELECT 1 FROM FormTranslationRevisionEntity ftr WHERE ftr.formTranslation = f AND ftr.revision = 1 AND ftr.createdAt > :createdAt)"
	)
	fun updateDeletedAtAndDeletedWhenCreatedAtGreaterThan(
		formPath: String,
		createdAt: LocalDateTime,
		deletedAt: LocalDateTime,
		deletedBy: String
	): Int

}
