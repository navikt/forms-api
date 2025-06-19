package no.nav.forms.forms.repository.entity

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import no.nav.forms.forms.repository.converter.DbJsonbConverter
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import kotlin.jvm.javaClass

@Entity
@Table(name = "form_attribute")
open class FormAttributeEntity (
	@Column(name = "name", columnDefinition = "varchar", nullable = false, updatable = false)
	val name: FormAttributeName,

	@Convert(converter = DbJsonbConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "value", columnDefinition = "jsonb", nullable = false, updatable = false)
	val value: JsonNode,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	val id: Long? = null
) {

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
		other as FormEntity

		return id != null && id == other.id
	}

	override fun hashCode(): Int = javaClass.hashCode()

	@Override
	override fun toString(): String {
		return this::class.simpleName + "(id = $id, name = $name)"
	}

}
