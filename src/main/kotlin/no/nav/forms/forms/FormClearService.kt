package no.nav.forms.forms

import no.nav.forms.exceptions.ConflictException
import no.nav.forms.exceptions.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@EnableScheduling
@Service
class FormClearService(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val json: ObjectMapper,
    private val environment: Environment,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val leaseSeconds = 60

    fun preview(request: FormClearRequest): FormClearPreview {
        val paths = jdbc.query(
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
        ) { row, _ -> Triple(row.getString("path"), row.getBoolean("locked"), row.getBoolean("test_form")) }
        val explicit = request.keepFormPaths.toSet()
        val kept = paths.filter { (path, locked, test) ->
            path in explicit || (request.keepLockedForms && locked) || (request.keepTestForms && test)
        }.map { it.first }
        return FormClearPreview(paths.map { it.first } - kept.toSet(), kept)
    }

    fun start(request: FormClearStartRequest, actor: String): UUID {
        val keepRequest = request.keepRequest()
        validateExpectedPaths(request.expectedToDelete, request.expectedKept)
        val id = UUID.randomUUID()
        try {
            transactions.executeWithoutResult {
                jdbc.execute("LOCK TABLE form, form_revision, form_attribute IN SHARE MODE")
                val plan = preview(keepRequest)
                if (plan.toDelete.toSet() != request.expectedToDelete.toSet() ||
                    plan.kept.toSet() != request.expectedKept.toSet()
                ) throw ConflictException("Form clear plan changed; request a new preview")
                jdbc.update(
                    """
                    INSERT INTO form_clear_job (id, status, keep_set, target_paths, created_by)
                    VALUES (?, 'pending', ?::jsonb, ?::jsonb, ?)
                    """.trimIndent(),
                    id, json.writeValueAsString(keepRequest), json.writeValueAsString(plan.toDelete), actor
                )
                plan.kept.forEach { path ->
                    jdbc.update(
                        "INSERT INTO form_clear_job_item (job_id, path, outcome) VALUES (?, ?, 'kept')",
                        id, path
                    )
                }
                logger.info(
                    "Form clear started job={} actor={} profiles={} cluster={} targets={} kept={}",
                    id, actor, environment.activeProfiles.joinToString(","), environment.getProperty("NAIS_CLUSTER_NAME"),
                    plan.toDelete.size, plan.kept.size
                )
            }
        } catch (exception: DataIntegrityViolationException) {
            if (jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM form_clear_job WHERE status IN ('pending', 'running'))",
                    Boolean::class.java
                ) == true
            ) throw ConflictException("A form clear job is already active")
            throw exception
        }
        return id
    }

    private fun validateExpectedPaths(toDelete: List<String>, kept: List<String>) {
        val paths = toDelete + kept
        require(paths.all { it.isNotBlank() && it.length <= 24 }) {
            "Invalid expected form path"
        }
        require(paths.size == paths.toSet().size) { "Duplicate expected form path" }
    }

    fun activeJob(): FormClearJob {
        val id = jdbc.query(
            "SELECT id FROM form_clear_job WHERE status IN ('pending', 'running')",
            { row, _ -> row.getObject("id", UUID::class.java) }
        ).firstOrNull() ?: throw ResourceNotFoundException("No active form clear job", "active")
        return status(id)
    }

    fun status(id: UUID): FormClearJob {
        val row = jdbc.query(
            "SELECT status, target_paths FROM form_clear_job WHERE id = ?",
            { result, _ -> result.getString("status") to result.getString("target_paths") }, id
        ).firstOrNull() ?: throw ResourceNotFoundException("Form clear job not found", id.toString())
        val targets = json.readValue(row.second, Array<String>::class.java).size
        val items = jdbc.query(
            "SELECT path, outcome, error FROM form_clear_job_item WHERE job_id = ? ORDER BY path",
            { result, _ ->
                FormClearItem(result.getString("path"), result.getString("outcome"), result.getString("error"))
            }, id
        )
        return FormClearJob(
            id, row.first, targets + items.count { it.outcome == "kept" }, items.size,
            items.count { it.outcome == "deleted" }, items.count { it.outcome == "kept" },
            items.count { it.outcome == "failed" }, items
        )
    }

    @Scheduled(fixedDelay = 1000)
    fun poll() {
        if (environment.getProperty("form-clear.poll.enabled", Boolean::class.java, true) == false ||
            environment.activeProfiles.contains("prod") || environment.getProperty("NAIS_CLUSTER_NAME") == "prod-gcp"
        ) return
        processNextJob()
    }

    fun processNextJob() {
        try {
            val claim = claim() ?: return
            val (id, owner, targets) = claim
            for (path in targets) {
                if (jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM form_clear_job_item WHERE job_id = ? AND path = ?)",
                        Boolean::class.java, id, path
                    ) == true
                ) continue
                try {
                    transactions.executeWithoutResult {
                        fence(id, owner)
                        deleteForm(path)
                        jdbc.update(
                            "INSERT INTO form_clear_job_item (job_id, path, outcome) VALUES (?, ?, 'deleted') ON CONFLICT DO NOTHING",
                            id, path
                        )
                    }
                } catch (_: LostFormClearLease) {
                    return
                } catch (exception: Exception) {
                    val error = "Deletion failed (${exception.javaClass.simpleName})"
                    try {
                        transactions.executeWithoutResult {
                            fence(id, owner)
                            jdbc.update(
                                "INSERT INTO form_clear_job_item (job_id, path, outcome, error) VALUES (?, ?, 'failed', ?) ON CONFLICT DO NOTHING",
                                id, path, error
                            )
                        }
                    } catch (_: LostFormClearLease) {
                        return
                    }
                    logger.warn("Form clear failed job={} path={} cause={}", id, path, error)
                }
            }
            transactions.executeWithoutResult {
                fence(id, owner)
                jdbc.update(
                    "UPDATE form_clear_job SET status = 'completed', owner = NULL, lease_until = NULL, finished_at = now() WHERE id = ? AND owner = ?",
                    id, owner
                )
            }
            val result = status(id)
            logger.info(
                "Form clear finished job={} actor={} profiles={} cluster={} deleted={} kept={} failed={}",
                id, jdbc.queryForObject("SELECT created_by FROM form_clear_job WHERE id = ?", String::class.java, id),
                environment.activeProfiles.joinToString(","), environment.getProperty("NAIS_CLUSTER_NAME"),
                result.deletedCount, result.keptCount, result.failedCount
            )
        } catch (_: LostFormClearLease) {
            // Another pod owns the job.
        } catch (exception: Exception) {
            logger.error("Form clear polling failed", exception)
        }
    }

    private fun claim(): Triple<UUID, String, List<String>>? = transactions.execute {
        val candidate = jdbc.query(
            """
            SELECT id, target_paths FROM form_clear_job
            WHERE status = 'pending' OR (status = 'running' AND lease_until < now())
            ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
            """.trimIndent(),
            { row, _ -> row.getObject("id", UUID::class.java) to row.getString("target_paths") }
        ).firstOrNull() ?: return@execute null
        val owner = "${environment.getProperty("HOSTNAME", "unknown")}/${UUID.randomUUID()}"
        jdbc.update(
            "UPDATE form_clear_job SET status = 'running', owner = ?, lease_until = now() + (? * interval '1 second') WHERE id = ?",
            owner, leaseSeconds, candidate.first
        )
        Triple(candidate.first, owner, json.readValue(candidate.second, Array<String>::class.java).toList())
    }

    private fun fence(id: UUID, owner: String) {
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

    private fun deleteForm(path: String) {
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

    private class LostFormClearLease : RuntimeException()
}
