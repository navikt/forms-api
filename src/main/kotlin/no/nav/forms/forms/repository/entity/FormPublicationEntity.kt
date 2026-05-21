package no.nav.forms.forms.repository.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import no.nav.forms.forms.repository.converter.DbJsonArrayConverter
import no.nav.forms.translations.form.repository.entity.PublishedFormTranslationsEntity
import no.nav.forms.translations.global.repository.entity.PublishedGlobalTranslationsEntity
import org.hibernate.Hibernate
import org.hibernate.annotations.DynamicInsert
import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@DynamicInsert
@Table(name = "form_publication")
class FormPublicationEntity(
	@Column(
		name = "created_at",
		columnDefinition = "TIMESTAMP WITH TIME ZONE",
		nullable = false
	)
	val createdAt: LocalDateTime,

	@Column(name = "created_by", columnDefinition = "varchar", nullable = false)
	val createdBy: String,

	@JdbcTypeCode(SqlTypes.UUID)
	@Column(name = "publication_id", columnDefinition = "uuid", nullable = false, unique = true)
	val publicationId: UUID? = null,

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "form_revision_id", nullable = false)
	val formRevision: FormRevisionEntity,

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "form_id", nullable = false)
	val form: FormEntity,

	@Fetch(FetchMode.JOIN)
	@OneToOne(optional = false)
	@JoinColumn(name = "published_form_translation_id", referencedColumnName = "id", nullable = false)
	val publishedFormTranslation: PublishedFormTranslationsEntity,

	@Fetch(FetchMode.JOIN)
	@OneToOne(optional = false)
	@JoinColumn(name = "published_global_translation_id", referencedColumnName = "id", nullable = false)
	val publishedGlobalTranslation: PublishedGlobalTranslationsEntity,

	@Convert(converter = DbJsonArrayConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "languages", columnDefinition = "jsonb", nullable = true)
	val languages: JsonNode,

	@Column(name = "status", columnDefinition = "varchar", nullable = false)
	val status: FormPublicationStatusDb,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	val id: Long? = null,
) {

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
		other as FormPublicationEntity

		return id != null && id == other.id
	}

	override fun hashCode(): Int = javaClass.hashCode()

	@Override
	override fun toString(): String {
		return this::class.simpleName + "(id = $id, createdAt = $createdAt, createdBy = $createdBy)"
	}

	fun copy(
		createdAt: LocalDateTime,
		createdBy: String,
		status: FormPublicationStatusDb,
	): FormPublicationEntity {
		return FormPublicationEntity(
			id = null,
			createdAt = createdAt,
			createdBy = createdBy,
			publicationId = null,
			formRevision = this.formRevision,
			form = this.form,
			publishedFormTranslation = this.publishedFormTranslation,
			publishedGlobalTranslation = this.publishedGlobalTranslation,
			languages = this.languages,
			status = status,
		)
	}

}
