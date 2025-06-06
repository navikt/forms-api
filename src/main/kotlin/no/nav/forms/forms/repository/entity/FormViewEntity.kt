package no.nav.forms.forms.repository.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import no.nav.forms.forms.repository.converter.DbJsonbConverter
import no.nav.forms.forms.repository.converter.FormLockConverter
import no.nav.forms.forms.repository.entity.attributes.FormLockDb
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "form_view")
class FormViewEntity(
	@Id @Column(name = "id")
	val id: Long,

	@Column(name = "current_rev_id", updatable = false)
	val currentRevisionId: Long,

	@Column(name = "revision", columnDefinition = "int", nullable = false, updatable = false)
	val revision: Int,

	@Column(name = "skjemanummer", columnDefinition = "varchar", nullable = false, updatable = false)
	val skjemanummer: String,

	@Column(name = "path", columnDefinition = "varchar", nullable = false, updatable = false)
	val path: String,

	@Column(name = "title", columnDefinition = "varchar", nullable = false, updatable = false)
	val title: String,

	@JdbcTypeCode(SqlTypes.JSON)
	@Convert(converter = FormLockConverter::class)
	@Column(name = "lock", columnDefinition = "jsonb", nullable = true, updatable = false)
	var lock: FormLockDb? = null,

	@Column(
		name = "changed_at",
		columnDefinition = "TIMESTAMP WITH TIME ZONE",
		nullable = false,
		updatable = false
	)
	val changedAt: LocalDateTime,

	@Column(name = "changed_by", columnDefinition = "varchar", nullable = false, updatable = false)
	val changedBy: String,

	@Column(
		name = "deleted_at",
		columnDefinition = "TIMESTAMP WITH TIME ZONE",
		nullable = true,
		updatable = false
	)
	val deletedAt: LocalDateTime? = null,

	@Column(name = "deleted_by", columnDefinition = "varchar", nullable = true, updatable = false)
	val deletedBy: String? = null,

	@Convert(converter = DbJsonbConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "properties", columnDefinition = "jsonb", nullable = false, updatable = false)
	val properties: JsonNode,

	@Column(name = "published_at", columnDefinition = "TIMESTAMP WITH TIME ZONE", updatable = false)
	val publishedAt: LocalDateTime? = null,

	@Column(name = "published_by", columnDefinition = "varchar", updatable = false)
	val publishedBy: String? = null,

	@Column(name = "published_rev_id", updatable = false)
	val publishedRevisionId: Long? = null,

	@Column(name = "publication_status", columnDefinition = "varchar", updatable = false)
	val publicationStatus: FormPublicationStatusDb? = null,
) {

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
		other as FormViewEntity

		return id == other.id
	}

	override fun hashCode(): Int = javaClass.hashCode()

	@Override
	override fun toString(): String {
		return this::class.simpleName + "(id = $id, skjemanummer = $skjemanummer)"
	}

}
