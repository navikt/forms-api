package no.nav.forms.translations.global.repository

import no.nav.forms.translations.global.repository.entity.EditableGlobalTranslationRow
import no.nav.forms.translations.global.repository.entity.GlobalTranslationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface GlobalTranslationRepository : JpaRepository<GlobalTranslationEntity, Long> {

	fun findByKey(key: String): GlobalTranslationEntity?

	fun findAllByDeletedAtIsNull(): List<GlobalTranslationEntity>

	fun findByIdAndDeletedAtIsNull(id: Long): GlobalTranslationEntity?

	@Query(
		value = """
			WITH latest_published_revision AS (
				SELECT
					gtr.global_translation_id AS global_translation_id,
					MAX(gtr.revision) AS revision
				FROM global_translation_revision gtr
				JOIN published_global_translation_revision pgtr
					ON pgtr.global_translation_revision_id = gtr.id
				GROUP BY gtr.global_translation_id
			),
			latest_publication AS (
				SELECT DISTINCT ON (gtr.global_translation_id)
					gtr.global_translation_id AS "id",
					pgt.created_at AS "publishedAt",
					pgt.created_by AS "publishedBy"
				FROM global_translation_revision gtr
				JOIN published_global_translation_revision pgtr
					ON pgtr.global_translation_revision_id = gtr.id
				JOIN published_global_translation pgt
					ON pgt.id = pgtr.published_global_translation_id
				JOIN latest_published_revision lpr
					ON lpr.global_translation_id = gtr.global_translation_id
					AND lpr.revision = gtr.revision
				ORDER BY gtr.global_translation_id, pgt.created_at DESC, pgt.id DESC
			)
			SELECT
				gt.id AS "id",
				gt.key AS "key",
				gt.tag AS "tag",
				gtr.revision AS "revision",
				gtr.nb AS "nb",
				gtr.nn AS "nn",
				gtr.en AS "en",
				gtr.created_at AS "changedAt",
				gtr.created_by AS "changedBy",
				lp."publishedAt" AS "publishedAt",
				lp."publishedBy" AS "publishedBy"
			FROM global_translation gt
			JOIN global_translation_revision gtr
				ON gtr.global_translation_id = gt.id
				AND gtr.revision = (
					SELECT MAX(current_revision.revision)
					FROM global_translation_revision current_revision
					WHERE current_revision.global_translation_id = gt.id
				)
			LEFT JOIN latest_publication lp
				ON lp."id" = gt.id
			WHERE gt.deleted_at IS NULL
			ORDER BY gt.id
		""",
		nativeQuery = true,
	)
	fun findEditableGlobalTranslations(): List<EditableGlobalTranslationRow>

}
