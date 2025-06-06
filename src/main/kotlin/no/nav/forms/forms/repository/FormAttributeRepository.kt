package no.nav.forms.forms.repository

import no.nav.forms.forms.repository.entity.FormAttributeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FormAttributeRepository : JpaRepository<FormAttributeEntity, Long>
