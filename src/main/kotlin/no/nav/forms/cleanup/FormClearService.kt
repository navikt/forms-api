package no.nav.forms.cleanup

import no.nav.forms.exceptions.ConflictException
import no.nav.forms.exceptions.ResourceNotFoundException
import no.nav.forms.model.DatabaseCleanupJob
import no.nav.forms.model.DatabaseCleanupJobItemsInner
import no.nav.forms.model.DatabaseCleanupPreview
import no.nav.forms.model.DatabaseCleanupRequest
import no.nav.forms.model.DatabaseCleanupStartRequest
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@EnableScheduling
@Service
class FormClearService(
    private val repository: FormClearRepository,
    private val transactions: TransactionTemplate,
    private val environment: Environment,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val leaseSeconds = 60

    fun preview(request: DatabaseCleanupRequest): DatabaseCleanupPreview {
        val paths = repository.findForms()
        val explicit = request.keepFormPaths.toSet()
        val kept = paths.filter { form ->
            form.path in explicit || (request.keepLockedForms && form.locked) ||
                (request.keepTestForms && form.testForm)
        }.map { it.path }
        return DatabaseCleanupPreview(paths.map { it.path } - kept.toSet(), kept)
    }

    fun start(request: DatabaseCleanupStartRequest, actor: String): UUID {
        val keepRequest = DatabaseCleanupRequest(request.keepTestForms, request.keepLockedForms, request.keepFormPaths)
        validateExpectedPaths(request.expectedToDelete, request.expectedKept)
        val id = UUID.randomUUID()
        try {
            transactions.executeWithoutResult {
                repository.lockFormsForPlanning()
                val plan = preview(keepRequest)
                if (plan.toDelete.toSet() != request.expectedToDelete.toSet() ||
                    plan.kept.toSet() != request.expectedKept.toSet()
                ) throw ConflictException("Form clear plan changed; request a new preview")
                repository.insertJob(id, keepRequest, plan.toDelete, actor)
                plan.kept.forEach { repository.insertKeptItem(id, it) }
                logger.info(
                    "Form clear started job={} actor={} profiles={} cluster={} targets={} kept={}",
                    id, actor, environment.activeProfiles.joinToString(","), environment.getProperty("NAIS_CLUSTER_NAME"),
                    plan.toDelete.size, plan.kept.size
                )
            }
        } catch (exception: DataIntegrityViolationException) {
            if (repository.hasActiveJob()) throw ConflictException("A form clear job is already active")
            throw exception
        }
        return id
    }

    private fun validateExpectedPaths(toDelete: List<String>, kept: List<String>) {
        val paths = toDelete + kept
        require(paths.all { it.isNotBlank() && it.length <= 24 }) { "Invalid expected form path" }
        require(paths.size == paths.toSet().size) { "Duplicate expected form path" }
    }

    fun activeJob(): DatabaseCleanupJob {
        val id = repository.findActiveJobId()
            ?: throw ResourceNotFoundException("No active form clear job", "active")
        return status(id)
    }

    fun status(id: UUID): DatabaseCleanupJob {
        val job = repository.findJob(id)
            ?: throw ResourceNotFoundException("Form clear job not found", id.toString())
        val items = repository.findItems(id)
        val keptCount = items.count { it.outcome == "kept" }
        return DatabaseCleanupJob(
            id.toString(), DatabaseCleanupJob.Status.valueOf(job.status),
            job.targetCount + keptCount, items.size,
            items.count { it.outcome == "deleted" }, keptCount,
            items.count { it.outcome == "failed" },
            items.map { DatabaseCleanupJobItemsInner(
                it.path, DatabaseCleanupJobItemsInner.Outcome.valueOf(it.outcome), it.error
            ) }
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
            val owner = "${environment.getProperty("HOSTNAME", "unknown")}/${UUID.randomUUID()}"
            val claim = transactions.execute { repository.claim(owner, leaseSeconds) } ?: return
            val (id, claimedOwner, targets) = claim
            for (path in targets) {
                if (repository.hasItem(id, path)) continue
                try {
                    transactions.executeWithoutResult {
                        repository.fence(id, claimedOwner, leaseSeconds)
                        repository.deleteForm(path)
                        repository.insertOutcome(id, path, "deleted")
                    }
                } catch (_: LostFormClearLease) {
                    return
                } catch (exception: Exception) {
                    val error = "Deletion failed (${exception.javaClass.simpleName})"
                    try {
                        transactions.executeWithoutResult {
                            repository.fence(id, claimedOwner, leaseSeconds)
                            repository.insertOutcome(id, path, "failed", error)
                        }
                    } catch (_: LostFormClearLease) {
                        return
                    }
                    logger.warn("Form clear failed job={} path={} cause={}", id, path, error)
                }
            }
            transactions.executeWithoutResult {
                repository.fence(id, claimedOwner, leaseSeconds)
                repository.complete(id, claimedOwner)
            }
            val result = status(id)
            logger.info(
                "Form clear finished job={} actor={} profiles={} cluster={} deleted={} kept={} failed={}",
                id, repository.createdBy(id),
                environment.activeProfiles.joinToString(","), environment.getProperty("NAIS_CLUSTER_NAME"),
                result.deletedCount, result.keptCount, result.failedCount
            )
        } catch (_: LostFormClearLease) {
            // Another pod owns the job.
        } catch (exception: Exception) {
            logger.error("Form clear polling failed", exception)
        }
    }
}
