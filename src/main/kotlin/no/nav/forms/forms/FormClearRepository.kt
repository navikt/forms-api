package no.nav.forms.forms

import no.nav.forms.model.DatabaseCleanupRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

data class FormClearCandidate(val path: String, val locked: Boolean, val testForm: Boolean)
data class FormClearJobRecord(val status: String, val targetCount: Int)
data class FormClearItemRecord(val path: String, val outcome: String, val error: String?)
data class FormClearClaim(val id: UUID, val owner: String, val targets: List<String>)

class LostFormClearLease : RuntimeException()

@Repository
class FormClearRepository(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) {
    fun findForms(): List<FormClearCandidate> = jdbc.query(
        """
        SELECT f.path, f.lock IS NOT NULL AS locked,
               COALESCE(a.value->>'isTestForm', 'false') = 'true' AS test_form
        FROM form f
        LEFT JOIN LATERAL (
            SELECT r.properties_id FROM form_revision r
            WHERE r.form_id = f.id ORDER BY r.revision DESC LIMIT 1
        ) r ON true
        LEFT JOIN form_attribute a ON a.id = r.properties_id
        ORDER BY f.path
        """.trimIndent()
    ) { row, _ ->
        FormClearCandidate(row.getString("path"), row.getBoolean("locked"), row.getBoolean("test_form"))
    }

    fun lockFormsForPlanning() {
        jdbc.execute("LOCK TABLE form, form_revision, form_attribute IN SHARE MODE")
    }

    fun insertJob(id: UUID, keep: DatabaseCleanupRequest, targets: List<String>, actor: String) {
        jdbc.update(
            """
            INSERT INTO form_clear_job (id, status, keep_set, target_paths, created_by)
            VALUES (?, 'pending', ?::jsonb, ?::jsonb, ?)
            """.trimIndent(),
            id, json.writeValueAsString(keep), json.writeValueAsString(targets), actor
        )
    }

    fun insertKeptItem(id: UUID, path: String) {
        jdbc.update("INSERT INTO form_clear_job_item (job_id, path, outcome) VALUES (?, ?, 'kept')", id, path)
    }

    fun hasActiveJob(): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM form_clear_job WHERE status IN ('pending', 'running'))",
        Boolean::class.java
    ) == true

    fun findActiveJobId(): UUID? = jdbc.query(
        "SELECT id FROM form_clear_job WHERE status IN ('pending', 'running')",
        { row, _ -> row.getObject("id", UUID::class.java) }
    ).firstOrNull()

    fun findJob(id: UUID): FormClearJobRecord? = jdbc.query(
        "SELECT status, target_paths FROM form_clear_job WHERE id = ?",
        { row, _ ->
            FormClearJobRecord(
                row.getString("status"),
                json.readValue(row.getString("target_paths"), Array<String>::class.java).size
            )
        }, id
    ).firstOrNull()

    fun findItems(id: UUID): List<FormClearItemRecord> = jdbc.query(
        "SELECT path, outcome, error FROM form_clear_job_item WHERE job_id = ? ORDER BY path",
        { row, _ ->
            FormClearItemRecord(row.getString("path"), row.getString("outcome"), row.getString("error"))
        }, id
    )

    fun hasItem(id: UUID, path: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM form_clear_job_item WHERE job_id = ? AND path = ?)",
        Boolean::class.java, id, path
    ) == true

    fun insertOutcome(id: UUID, path: String, outcome: String, error: String? = null) {
        jdbc.update(
            "INSERT INTO form_clear_job_item (job_id, path, outcome, error) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            id, path, outcome, error
        )
    }

    fun complete(id: UUID, owner: String) {
        jdbc.update(
            "UPDATE form_clear_job SET status = 'completed', owner = NULL, lease_until = NULL, finished_at = now() WHERE id = ? AND owner = ?",
            id, owner
        )
    }

    fun createdBy(id: UUID): String? =
        jdbc.queryForObject("SELECT created_by FROM form_clear_job WHERE id = ?", String::class.java, id)

    fun claim(owner: String, leaseSeconds: Int): FormClearClaim? {
        val candidate = jdbc.query(
            """
            SELECT id, target_paths FROM form_clear_job
            WHERE status = 'pending' OR (status = 'running' AND lease_until < now())
            ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
            """.trimIndent(),
            { row, _ -> row.getObject("id", UUID::class.java) to row.getString("target_paths") }
        ).firstOrNull() ?: return null
        jdbc.update(
            "UPDATE form_clear_job SET status = 'running', owner = ?, lease_until = now() + (? * interval '1 second') WHERE id = ?",
            owner, leaseSeconds, candidate.first
        )
        return FormClearClaim(
            candidate.first, owner, json.readValue(candidate.second, Array<String>::class.java).toList()
        )
    }

    fun fence(id: UUID, owner: String, leaseSeconds: Int) {
        val valid = jdbc.query(
            "SELECT owner = ? AND lease_until > now() AND status = 'running' AS valid FROM form_clear_job WHERE id = ? FOR UPDATE",
            { row, _ -> row.getBoolean("valid") }, owner, id
        ).firstOrNull() == true
        if (!valid) throw LostFormClearLease()
        jdbc.update(
            "UPDATE form_clear_job SET lease_until = now() + (? * interval '1 second') WHERE id = ?",
            leaseSeconds, id
        )
    }

    fun deleteForm(path: String) {
        val ids = jdbc.query(
            "SELECT id FROM form WHERE path = ? FOR UPDATE",
            { row, _ -> row.getLong("id") }, path
        )
        if (ids.isEmpty()) return
        val id = ids.single()
        jdbc.update("DELETE FROM form_publication WHERE form_id = ?", id)
        jdbc.update(
            "DELETE FROM published_form_translation_revision WHERE published_form_translation_id IN (SELECT id FROM published_form_translation WHERE form_id = ?)",
            id
        )
        jdbc.update("DELETE FROM published_form_translation WHERE form_id = ?", id)
        jdbc.update(
            "DELETE FROM published_form_translation_revision WHERE form_translation_revision_id IN (SELECT r.id FROM form_translation_revision r JOIN form_translation t ON r.form_translation_id = t.id WHERE t.form_id = ?)",
            id
        )
        jdbc.update(
            "DELETE FROM form_translation_revision WHERE form_translation_id IN (SELECT id FROM form_translation WHERE form_id = ?)",
            id
        )
        jdbc.update("DELETE FROM form_translation WHERE form_id = ?", id)
        val attributes = jdbc.query(
            "SELECT components_id, properties_id, intro_page_id FROM form_revision WHERE form_id = ?",
            { row, _ ->
                listOfNotNull(row.getObject("components_id") as? Long, row.getObject("properties_id") as? Long,
                    row.getObject("intro_page_id") as? Long)
            }, id
        ).flatten()
        jdbc.update("DELETE FROM form_revision WHERE form_id = ?", id)
        attributes.forEach { jdbc.update("DELETE FROM form_attribute WHERE id = ?", it) }
        jdbc.update("DELETE FROM form WHERE id = ?", id)
    }
}
