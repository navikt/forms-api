package no.nav.forms.forms.repository.entity

import jakarta.persistence.*
import org.hibernate.Hibernate
import java.time.LocalDateTime

@Entity
@Table(name = "form_revision")
class FormRevisionEntity(
	@Column(name = "revision", columnDefinition = "int", nullable = false)
	val revision: Int,

	@Column(name = "title", columnDefinition = "varchar", nullable = false)
	val title: String,

	@Column(
		name = "created_at",
		columnDefinition = "TIMESTAMP WITH TIME ZONE",
		nullable = false
	)
	val createdAt: LocalDateTime,

	@Column(name = "created_by", columnDefinition = "varchar", nullable = false) val createdBy: String,

	/**
	 * Only map the id pointing to components json due to performance issues. Fetch type LAZY was not respected.
	 */
	@Column(name = "components_id", nullable = false)
	val componentsId: Long,

	@Column(name = "intro_page_id", nullable = true)
	val introPageId: Long? = null,

	@ManyToOne(fetch = FetchType.EAGER, optional = false)
	@JoinColumn(name = "properties_id", nullable = false)
	val properties: FormAttributeEntity,

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "form_id", nullable = false)
	val form: FormEntity,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	val id: Long? = null,
) {

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
		other as FormRevisionEntity

		return id != null && id == other.id
	}

	override fun hashCode(): Int = javaClass.hashCode()

	@Override
	override fun toString(): String {
		return this::class.simpleName + "(id = $id, revision = $revision, createdAt = $createdAt)"
	}
}
