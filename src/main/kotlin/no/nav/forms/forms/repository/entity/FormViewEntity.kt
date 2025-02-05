package no.nav.forms.forms.repository.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import no.nav.forms.forms.repository.converter.DbJsonObjectConverter
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "form_view")
data class FormViewEntity(
	@Id @Column(name = "id")
	val id: Long,

	@Column(name = "current_rev_id")
	val currentRevisionId: Long,

	@Column(name = "revision", columnDefinition = "int", nullable = false)
	val revision: Int,

	@Column(name = "skjemanummer", columnDefinition = "varchar", nullable = false)
	val skjemanummer: String,

	@Column(name = "path", columnDefinition = "varchar", nullable = false)
	val path: String,

	@Column(name = "title", columnDefinition = "varchar", nullable = false)
	val title: String,

	@Column(
		name = "changed_at",
		columnDefinition = "TIMESTAMP WITH TIME ZONE",
		nullable = false
	)
	val changedAt: LocalDateTime,

	@Column(name = "changed_by", columnDefinition = "varchar", nullable = false)
	val changedBy: String,

	@Convert(converter = DbJsonObjectConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "properties", columnDefinition = "jsonb", nullable = true)
	val properties: JsonNode,

	@Column(name = "published_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
	val publishedAt: LocalDateTime? = null,

	@Column(name = "published_by", columnDefinition = "varchar")
	val publishedBy: String? = null,

	@Column(name = "published_rev_id")
	val publishedRevisionId: Long? = null,

	@Column(name = "publication_status", columnDefinition = "varchar")
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
