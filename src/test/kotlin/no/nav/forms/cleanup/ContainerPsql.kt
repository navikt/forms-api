package no.nav.forms.cleanup

import org.testcontainers.containers.Container
import org.testcontainers.images.builder.Transferable
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ContainerPsql(private val container: PostgreSQLContainer) : AutoCloseable {

	private val executor = Executors.newCachedThreadPool()
	private val sessions = ConcurrentHashMap.newKeySet<Session>()

	init {
		container.copyFileToContainer(
			MountableFile.forHostPath(Path.of("scripts/preprod-cleanup").toAbsolutePath()),
			"/cleanup/scripts/preprod-cleanup",
		)
	}

	fun start(
		sql: String,
		confirmation: String = "DELETE preprod and preprod-alt\n",
		databaseName: String = container.databaseName,
		stopOnError: Boolean = true,
		expectedDatabase: String? = null,
	): Session {
		check(!executor.isShutdown) { "ContainerPsql is closed" }
		val directory = "/cleanup/sessions/${UUID.randomUUID()}"
		exec("mkdir", "-p", directory)
		copy("\\echo CONTAINER_PSQL_READY\n$sql", "$directory/driver.sql")
		copy(confirmation, "$directory/stdin")
		copy("", "$directory/output")
		val expectedDatabaseArguments = expectedDatabase?.let { arrayOf("-v", "cleanup_database=$it") } ?: emptyArray()
		val process = executor.submit<Int> {
			container.execInContainer(
				"sh", "-c", """
					directory=${'$'}1
					shift
					export PGPASSWORD="${'$'}POSTGRES_PASSWORD" PGCONNECT_TIMEOUT=5 PGAPPNAME="${'$'}directory"
					cd /cleanup || exit 1
					exec psql "${'$'}@" < "${'$'}directory/stdin" > "${'$'}directory/output" 2>&1
				""".trimIndent(),
				"container-psql", directory,
				"-X", "-v", "ON_ERROR_STOP=${if (stopOnError) 1 else 0}",
				*expectedDatabaseArguments,
				"--no-password", "-h", "127.0.0.1", "-p", "5432",
				"-U", container.username, "-d", databaseName, "-f", "$directory/driver.sql",
			).exitCode
		}
		val session = Session(directory, process)
		sessions.add(session)
		try {
			// psql connects before reading its driver. Cancellation can now target that backend.
			session.awaitOutput("CONTAINER_PSQL_READY")
			return session
		} catch (failure: Exception) {
			runCatching { session.close() }.onFailure(failure::addSuppressed)
			throw failure
		}
	}

	override fun close() {
		try {
			sessions.toList().forEach { it.close() }
		} finally {
			executor.shutdownNow()
			check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Container psql executor did not stop" }
		}
	}

	inner class Session internal constructor(
		private val directory: String,
		private val process: Future<Int>,
	) : AutoCloseable {
		private var closed = false
		private var result: Result? = null

		fun output(): String {
			result?.let { return it.output }
			check(!closed) { "psql session is closed" }
			return exec("cat", "$directory/output").stdout
		}

		fun awaitOutput(marker: String) {
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
			while (true) {
				val completed = process.isDone
				val current = output()
				if (current.contains(marker)) return
				check(!completed && System.nanoTime() < deadline) {
					"psql did not produce marker '$marker':\n$current"
				}
				Thread.sleep(20)
			}
		}

		fun finish(): Result {
			result?.let { return it }
			try {
				val completed = Result(process.get(190, TimeUnit.SECONDS), output())
				result = completed
				close()
				return completed
			} catch (failure: Exception) {
				val message = if (failure is TimeoutException) "Container psql exceeded 190 seconds" else "Container psql execution failed"
				val capturedOutput = runCatching { output() }.getOrDefault("Output unavailable")
				val reported = IllegalStateException("$message:\n$capturedOutput", failure)
				runCatching { close() }.onFailure(reported::addSuppressed)
				throw reported
			}
		}

		override fun close() {
			if (closed) return
			closed = true
			try {
				// A delayed exec cannot read the driver after cancellation.
				exec("rm", "-f", "$directory/driver.sql")
				// Closing a client socket alone need not cancel a query or release its locks.
				DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
					connection.prepareStatement("SELECT pg_terminate_backend(pid, 5000) FROM pg_stat_activity WHERE application_name = ?").use {
						it.setString(1, directory)
						it.execute()
					}
				}
				process.get(5, TimeUnit.SECONDS)
			} finally {
				process.cancel(true)
				sessions.remove(this)
				exec("rm", "-rf", directory)
			}
		}
	}

	data class Result(val exitCode: Int, val output: String)

	private fun copy(content: String, destination: String) =
		container.copyFileToContainer(Transferable.of(content.toByteArray(Charsets.UTF_8)), destination)

	private fun exec(vararg command: String): Container.ExecResult =
		container.execInContainer(*command).also {
			check(it.exitCode == 0) { "Container psql file operation failed (${it.exitCode})" }
		}
}
