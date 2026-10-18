package no.nav.forms.cleanup

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreprodCleanupSqlTest {

	@TempDir
	lateinit var temporaryDirectory: Path
	private lateinit var postgres: EmbeddedPostgres
	private val database = "forms-api-preprod-db"
	private val tables = listOf(
		"form", "form_attribute", "form_revision", "form_translation", "form_translation_revision",
		"published_form_translation", "published_form_translation_revision", "form_publication",
		"global_translation", "global_translation_revision", "published_global_translation",
		"published_global_translation_revision", "recipient",
	)

	@BeforeAll
	fun startDatabase() {
		val version = ProcessBuilder("psql", "--version").redirectErrorStream(true).start()
		assertTrue(version.waitFor(10, TimeUnit.SECONDS), "psql --version timed out")
		assertEquals(0, version.exitValue(), "Local psql is required; tests must not silently skip")
		postgres = EmbeddedPostgres.builder().setTag("18").start()
		postgres.postgresDatabase.connection.use { connection ->
			connection.createStatement().use { statement ->
				listOf(database, "forms-api-db", "other").forEach {
					statement.execute("CREATE DATABASE \"$it\"")
				}
			}
		}
	}

	@AfterAll
	fun stopDatabase() {
		if (::postgres.isInitialized) postgres.close()
	}

	@BeforeEach
	fun migrate() {
		listOf(database, "forms-api-db", "other").forEach { name ->
			Flyway.configure().dataSource(postgres.getDatabase(postgres.userName, name))
				.locations("filesystem:src/main/resources/db/migration").cleanDisabled(false).load().apply {
					clean()
					migrate()
				}
		}
		connection().use { connection ->
			connection.createStatement().use { it.execute(GLOBAL_FIXTURE) }
		}
	}

	@Test
	fun `removes published history revisions translations and soft deleted forms but preserves shared data`() {
		val target = form("remove", published = true)
		form("soft", deleted = true)
		val keep = form("keep", published = true)
		connection().use { connection ->
			connection.createStatement().use { statement ->
				// Cross-column references must protect attributes regardless of their name.
				statement.execute("UPDATE form_revision SET intro_page_id = (SELECT components_id FROM form_revision WHERE form_id = $target LIMIT 1) WHERE form_id = $keep")
				statement.execute("INSERT INTO form_attribute(name, value) VALUES ('components', '[\"unrelated orphan\"]')")
			}
		}
		val before = snapshot()
		val preserved = preservedFormSnapshot(keep)
		val result = psql("\\set keep_paths '[\"keep\"]'\n$preview\n$execute")
		result.succeeds()
		assertEquals(listOf("keep"), paths())
		assertEquals(preserved, preservedFormSnapshot(keep))
		val after = snapshot()
		listOf("global_translation", "global_translation_revision", "published_global_translation",
			"published_global_translation_revision", "recipient").forEach { assertEquals(before[it], after[it], it) }
		assertEquals(0, scalar("SELECT count(*) FROM form_revision WHERE form_id = $target").toInt())
		assertEquals(0, scalar("SELECT count(*) FROM form_publication WHERE form_id = $target").toInt())
		assertEquals(1, scalar("SELECT count(*) FROM form_attribute WHERE value = '[\"unrelated orphan\"]'::jsonb").toInt())
		assertEquals(5, scalar("SELECT count(*) FROM form_attribute").toInt())
	}

	@Test
	fun `keep rules are OR combined and preserve every row`() {
		form("explicit", published = true)
		form("test", test = true, published = true)
		form("locked", locked = true, published = true)
		form("overlap", test = true, locked = true, published = true)
		val before = snapshot()
		psql("\\set keep_paths '[\"explicit\",\"overlap\"]'\n$preview\n$execute").succeeds()
		assertEquals(before, snapshot())
		psql("\\set keep_test_forms false\n\\set keep_locked_forms false\n$preview\n$execute").succeeds()
		assertTrue(paths().isEmpty())
	}

	@Test
	fun `execute uses frozen preview inputs rather than changed psql variables`() {
		form("explicit", published = true)
		form("test", test = true, published = true)
		form("locked", locked = true, published = true)
		val before = snapshot()
		psql("""
			\set keep_paths '["explicit"]'
			$preview
			\set keep_paths '[]'
			\set keep_test_forms false
			\set keep_locked_forms false
			$execute
		""".trimIndent()).succeeds()
		assertEquals(before, snapshot())
	}

	@Test
	fun `preview changes no source rows and holds no write locks during user review`() {
		form("remove", published = true)
		val before = snapshot()
		val process = startPsql("$preview\n\\echo PREVIEW_READY\nSELECT pg_sleep(2);")
		awaitOutput(process, "PREVIEW_READY")
		assertEquals(before, snapshot())
		connection().use { connection ->
			connection.createStatement().use {
				it.execute("SET lock_timeout = '500ms'")
				it.execute("UPDATE form SET lock = '{}'::jsonb WHERE path = 'remove'")
			}
		}
		finish(process).succeeds()
	}

	@Test
	fun `rejects invalid keep paths before changing source rows`() {
		form("known", published = true)
		val before = snapshot()
		listOf("not-json", "{}", "null", "true", "123", "[1]", "[null]", "[\"\"]", "[\"  \"]", "[\"unknown\"]", "[\"known\",\"known\"]").forEach { input ->
			psql("\\set keep_paths '$input'\n$preview\n$execute").fails()
			assertEquals(before, snapshot(), input)
		}
		listOf("keep_test_forms", "keep_locked_forms").forEach { flag ->
			psql("\\set $flag invalid\n$preview\n$execute").fails()
			assertEquals(before, snapshot(), flag)
		}
	}

	@Test
	fun `rejects NULL paths and malformed latest properties rather than deleting uncertain forms`() {
		form("malformed", published = true)
		val invalidValues = listOf("[]", "null", "{\"isTestForm\":\"true\"}", "{\"isTestForm\":1}", "{\"isTestForm\":null}")
		invalidValues.forEach { value ->
			connection().use { connection ->
				connection.prepareStatement("UPDATE form_attribute SET value = ?::jsonb WHERE id = (SELECT properties_id FROM form_revision ORDER BY revision DESC LIMIT 1)").use {
					it.setString(1, value)
					it.executeUpdate()
				}
			}
			val before = snapshot()
			psql("$preview\n$execute").fails()
			assertEquals(before, snapshot(), value)
		}
		connection().use { it.createStatement().execute("UPDATE form SET path = NULL") }
		val before = snapshot()
		val nullPath = psql("$preview\n$execute")
		nullPath.fails()
		assertTrue(nullPath.output.contains("NULL form path"), nullPath.output)
		assertEquals(before, snapshot())
	}

	@Test
	fun `latest revision controls test protection`() {
		val id = form("changed-test", test = true)
		connection().use { connection ->
			val properties = insert(connection, "INSERT INTO form_attribute(name, value) VALUES ('properties', '{}') RETURNING id")
			connection.createStatement().execute("UPDATE form_revision SET properties_id = $properties WHERE form_id = $id AND revision = 2")
		}
		psql("$preview\n$execute").succeeds()
		assertTrue(paths().isEmpty())
	}

	@Test
	fun `stale plans reject protection revision translation publication and identity changes`() {
		val mutations = listOf(
			"UPDATE public.form SET lock = '{}'::jsonb",
			"UPDATE public.form_attribute SET value = '{\"isTestForm\":true}' WHERE name = 'properties'",
			"UPDATE public.form_revision SET title = 'changed' WHERE revision = 1",
			"UPDATE public.form_translation SET key = 'changed'",
			"UPDATE public.form_translation_revision SET nb = 'changed' WHERE global_translation_id IS NULL",
			"UPDATE public.published_form_translation SET created_by = 'changed'",
			"DELETE FROM public.published_form_translation_revision WHERE published_form_translation_id = (SELECT min(id) FROM public.published_form_translation)",
			"UPDATE public.form_publication SET status = 'unpublished'",
			"WITH new_form AS (INSERT INTO public.form(path, created_by) VALUES ('new-form', 'fixture') RETURNING id) INSERT INTO public.form_revision(form_id, revision, title, created_by, components_id, properties_id, intro_page_id) SELECT f.id, 1, r.title, r.created_by, r.components_id, r.properties_id, r.intro_page_id FROM new_form f CROSS JOIN public.form_revision r WHERE r.revision = 2",
			"INSERT INTO public.form_revision(form_id, revision, title, created_by, components_id, properties_id, intro_page_id) SELECT form_id, 3, title, created_by, components_id, properties_id, intro_page_id FROM public.form_revision WHERE revision = 2",
		)
		mutations.forEach { mutation ->
			migrate()
			form("remove", published = true)
			val result = psql("$preview\n$mutation;\n$execute")
			result.fails()
			assertTrue(result.output.contains("Source data changed since preview"), result.output)
			assertTrue("remove" in paths(), mutation)
			assertTrue(scalar("SELECT count(*) FROM form_revision").toInt() >= 2, mutation)
		}
		migrate()
		form("same-path")
		val result = psql("""
			$preview
			CREATE TEMP TABLE old_revisions AS SELECT * FROM public.form_revision;
			DELETE FROM public.form_translation_revision;
			DELETE FROM public.form_translation;
			DELETE FROM public.form_revision;
			DELETE FROM public.form;
			INSERT INTO public.form(path, created_by) VALUES ('same-path', 'fixture');
			INSERT INTO public.form_revision(form_id, revision, title, created_by, components_id, properties_id, intro_page_id)
			SELECT f.id, r.revision, r.title, r.created_by, r.components_id, r.properties_id, r.intro_page_id
			FROM public.form f CROSS JOIN old_revisions r;
			$execute
		""".trimIndent())
		result.fails()
		assertTrue(result.output.contains("Source data changed since preview"), result.output)
		assertEquals(listOf("same-path"), paths())
	}

	@Test
	fun `rejects caller transactions and AUTOCOMMIT off without committing caller writes`() {
		form("remove", published = true)
		val before = snapshot()
		listOf("BEGIN;", "\\set AUTOCOMMIT off").forEach { wrapper ->
			val result = psql("$wrapper\nUPDATE public.form SET lock = '{}'::jsonb;\n$preview\n$execute")
			result.fails()
			assertTrue(result.output.contains("invalid transaction termination") || result.output.contains("AUTOCOMMIT"), result.output)
			assertEquals(before, snapshot(), wrapper)
		}
	}

	@Test
	fun `injected failure rolls back all deletes even with ON_ERROR_ROLLBACK enabled`() {
		form("remove", published = true)
		connection().use { connection ->
			connection.createStatement().execute("""
				CREATE FUNCTION reject_cleanup() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
				BEGIN RAISE EXCEPTION 'injected cleanup failure'; END; ${'$'}${'$'};
				CREATE TRIGGER fail_cleanup BEFORE DELETE ON form_revision FOR EACH STATEMENT EXECUTE FUNCTION reject_cleanup();
			""".trimIndent())
		}
		val before = snapshot()
		val result = psql("\\set ON_ERROR_STOP off\n\\set ON_ERROR_ROLLBACK on\n$preview\n$execute\nCOMMIT;")
		result.fails()
		assertTrue(result.output.contains("injected cleanup failure"), result.output)
		assertEquals(before, snapshot())
		val interactive = psql("$preview\n\\set ON_ERROR_STOP off\n\\set ON_ERROR_ROLLBACK on\n$execute\n\\echo ERROR_ROLLBACK :ON_ERROR_ROLLBACK\nCOMMIT;", stopOnError = false)
		assertTrue(interactive.output.contains("injected cleanup failure"), interactive.output)
		assertTrue(interactive.output.contains("ERROR_ROLLBACK off"), interactive.output)
		assertTrue(!interactive.output.contains("Cleanup committed"), interactive.output)
		assertEquals(before, snapshot())
		connection().use { it.createStatement().execute("DROP TRIGGER fail_cleanup ON form_revision") }
		psql("$preview\n$execute").succeeds()
		assertTrue(paths().isEmpty())
	}

	@Test
	fun `cross form snapshot reference fails safely with full rollback`() {
		val target = form("remove", published = true)
		val keep = form("keep", published = true)
		connection().use { connection ->
			connection.prepareStatement("""
				INSERT INTO published_form_translation_revision(published_form_translation_id, form_translation_revision_id)
				SELECT p.id, r.id FROM published_form_translation p
				JOIN form_translation t ON t.form_id = ?
				JOIN form_translation_revision r ON r.form_translation_id = t.id
				WHERE p.form_id = ? ORDER BY p.id, r.id LIMIT 1
			""".trimIndent()).use {
				it.setLong(1, target)
				it.setLong(2, keep)
				assertEquals(1, it.executeUpdate())
			}
		}
		val before = snapshot()
		val result = psql("\\set keep_paths '[\"keep\"]'\n$preview\n$execute\nCOMMIT;")
		result.fails()
		assertTrue(result.output.contains("foreign key constraint"), result.output)
		assertEquals(before, snapshot())
	}

	@Test
	fun `requires exact confirmation and a preview in the same session`() {
		form("remove", published = true)
		val before = snapshot()
		psql(execute).fails()
		psql("$preview\n$execute", confirmation = "yes\n").fails()
		assertEquals(before, snapshot())
		psql(preview).succeeds()
		psql(execute).fails()
		assertEquals(before, snapshot())
	}

	@Test
	fun `wrong database guards reject production and unknown names`() {
		listOf("forms-api-db", "other").forEach { name ->
			form("remove", published = true, databaseName = name)
			val before = snapshot(name)
			psql(preview, databaseName = name).fails()
			psql(execute, databaseName = name).fails()
			assertEquals(before, snapshot(name), name)
		}
	}

	@Test
	fun `initially disabled error stopping never bypasses guard inside entry scripts`() {
		listOf("forms-api-db", "other").forEach { name ->
			form("remove", published = true, databaseName = name)
			val before = snapshot(name)
			listOf(preview, execute).forEach { entry ->
				val result = psql("$entry\nSELECT pg_catalog.to_regclass('pg_temp.cleanup_ready') IS NULL AS guard_rejected \\gset\n\\echo GUARD_REJECTED :guard_rejected",
					databaseName = name, stopOnError = false)
				assertTrue(result.output.contains("Cleanup requires database forms-api-preprod-db"), result.output)
				assertTrue(result.output.contains("GUARD_REJECTED t"), result.output)
				assertTrue(!result.output.contains("Preview only."), result.output)
				assertTrue(!result.output.contains("Irreversible deletion after COMMIT"), result.output)
				assertEquals(before, snapshot(name))
			}
		}
	}

	@Test
	fun `nested capture failure stops outer driver without running any following command`() {
		form("malformed", published = true)
		connection().use { it.createStatement().execute("UPDATE form SET path = NULL") }
		val before = snapshot()
		val result = psql(preview)
		result.fails()
		assertTrue(result.output.contains("NULL form path"), result.output)
		assertEquals(before, snapshot())
	}

	@Test
	fun `rerunning a fresh preview and execute is safe after successful cleanup`() {
		form("remove", published = true)
		repeat(2) { psql("$preview\n$execute").succeeds() }
		assertTrue(paths().isEmpty())
	}

	@Test
	fun `API writer and competing cleanup cause bounded lock failures without partial deletes`() {
		form("remove", published = true)
		val before = snapshot()
		connection().use { writer ->
			writer.autoCommit = false
			writer.createStatement().execute("UPDATE form SET lock = '{}'::jsonb WHERE path = 'remove'")
			val elapsed = measureTime {
				val result = psql("$preview\n$execute")
				result.fails()
				assertTrue(result.output.contains("lock timeout"), result.output)
			}
			assertTrue(elapsed.inWholeSeconds in 4..15, "Lock wait was $elapsed")
			writer.rollback()
		}
		assertEquals(before, snapshot())
		connection().use { cleanup ->
			cleanup.autoCommit = false
			cleanup.createStatement().execute("LOCK TABLE form IN SHARE ROW EXCLUSIVE MODE")
			psql("$preview\n$execute").fails()
			connection().use { writer ->
				writer.createStatement().execute("SET lock_timeout = '200ms'")
				val failure = runCatching { writer.createStatement().execute("UPDATE form SET lock = '{}'::jsonb") }.exceptionOrNull()
				assertTrue(failure?.message?.contains("lock timeout") == true, failure.toString())
			}
			cleanup.rollback()
		}
		assertEquals(before, snapshot())
		psql("$preview\n$execute").succeeds()
	}

	@Test
	fun `actual cleanup holds all write locks and bounded timeouts until commit`() {
		form("remove", published = true)
		connection().use { connection ->
			connection.createStatement().execute("""
				CREATE FUNCTION inspect_cleanup() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
				BEGIN
				    IF current_setting('lock_timeout') <> '5s'
				        OR current_setting('statement_timeout') <> '2min'
				        OR current_setting('transaction_timeout') <> '3min' THEN
				        RAISE EXCEPTION 'Unexpected cleanup timeouts';
				    END IF;
				    IF (SELECT count(*) FROM pg_locks WHERE pid = pg_backend_pid()
				        AND mode = 'ShareRowExclusiveLock' AND granted) <> 8 THEN
				        RAISE EXCEPTION 'Missing cleanup table locks';
				    END IF;
				    PERFORM pg_advisory_xact_lock(850018);
				    RETURN NULL;
				END; ${'$'}${'$'};
				CREATE TRIGGER inspect_cleanup BEFORE DELETE ON form_publication
				FOR EACH STATEMENT EXECUTE FUNCTION inspect_cleanup();
			""".trimIndent())
		}
		connection().use { coordinator ->
			fun coordinatorSql(sql: String) { coordinator.createStatement().use { it.execute(sql) } }
			fun awaitDatabaseCondition(sql: String) {
				val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
				while (scalar(sql) != "t" && System.nanoTime() < deadline) Thread.sleep(20)
				assertEquals("t", scalar(sql), sql)
			}
			coordinatorSql("SELECT pg_advisory_lock(850018), pg_advisory_lock(850019), pg_advisory_lock(850020)")
			val first = startPsql("$preview\n\\echo FIRST_PREVIEW_READY\nSELECT pg_catalog.pg_advisory_lock(850019);\n$execute")
			val second = startPsql("$preview\n\\echo SECOND_PREVIEW_READY\nSELECT pg_catalog.pg_advisory_lock(850020);\n\\echo SECOND_EXECUTING\n$execute")
			try {
				awaitOutput(first, "FIRST_PREVIEW_READY")
				awaitOutput(second, "SECOND_PREVIEW_READY")
				coordinatorSql("SELECT pg_advisory_unlock(850019)")
				awaitDatabaseCondition("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'advisory' AND query LIKE 'DELETE FROM public.form_publication%')")
				coordinatorSql("SELECT pg_advisory_unlock(850020)")
				awaitOutput(second, "SECOND_EXECUTING")
				awaitDatabaseCondition("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE relation = 'form'::regclass AND mode = 'ShareRowExclusiveLock' AND NOT granted)")
				connection().use { writer ->
					writer.createStatement().execute("SET lock_timeout = '200ms'")
					val failure = runCatching { writer.createStatement().execute("INSERT INTO form(path, created_by) VALUES ('concurrent', 'fixture')") }.exceptionOrNull()
					assertTrue(failure?.message?.contains("lock timeout") == true, failure.toString())
				}
				coordinatorSql("SELECT pg_advisory_unlock(850018)")
				finish(first).succeeds()
				val competing = finish(second)
				competing.fails()
				assertTrue(competing.output.contains("Source data changed since preview"), competing.output)
				assertTrue(paths().isEmpty())
			} finally {
				listOf(first, second).forEach { if (it.process.isAlive) it.process.destroyForcibly().waitFor() }
			}
		}
	}

	@Test
	fun `representative thousand form cleanup with multiple revisions finishes within transaction budget`() {
		connection().use { connection ->
			connection.createStatement().execute("""
				INSERT INTO form(path, created_by, lock) SELECT 'benchmark-' || n, 'fixture',
				CASE WHEN n <= 500 THEN '{}'::jsonb END FROM generate_series(1, 1000) n;
				DO ${'$'}${'$'}
				DECLARE f record; rev integer; components bigint; properties bigint; intro bigint; translation bigint; tr bigint; snapshot bigint; current_revision bigint;
				BEGIN
				    FOR f IN SELECT id FROM form LOOP
				        INSERT INTO form_translation(form_id, key) VALUES (f.id, 'benchmark') RETURNING id INTO translation;
				        FOR rev IN 1..3 LOOP
				            INSERT INTO form_attribute(name, value) VALUES ('components',
				                jsonb_build_array(jsonb_build_object('key', f.id || '-' || rev, 'content', repeat(md5(f.id || '-' || rev), 128)))) RETURNING id INTO components;
				            INSERT INTO form_attribute(name, value) VALUES ('properties', jsonb_build_object('revision', rev)) RETURNING id INTO properties;
				            intro := NULL;
				            IF rev % 2 = 1 THEN
				                INSERT INTO form_attribute(name, value) VALUES ('introPage', jsonb_build_object('revision', rev)) RETURNING id INTO intro;
				            END IF;
				            INSERT INTO form_revision(form_id, revision, title, created_by, components_id, properties_id, intro_page_id)
				            VALUES (f.id, rev, 'Benchmark', 'fixture', components, properties, intro) RETURNING id INTO current_revision;
				            INSERT INTO form_translation_revision(form_translation_id, revision, nb, created_by)
				            VALUES (translation, rev, 'Benchmark', 'fixture') RETURNING id INTO tr;
				            INSERT INTO published_form_translation(form_id, created_by) VALUES (f.id, 'fixture') RETURNING id INTO snapshot;
				            INSERT INTO published_form_translation_revision VALUES (snapshot, tr);
				            INSERT INTO form_publication(form_id, form_revision_id, published_form_translation_id, published_global_translation_id, created_by)
				            VALUES (f.id, current_revision, snapshot, (SELECT id FROM published_global_translation LIMIT 1), 'fixture');
				        END LOOP;
				    END LOOP;
				END; ${'$'}${'$'};
			""".trimIndent())
		}
		assertEquals(3000, scalar("SELECT count(*) FROM form_revision").toInt())
		assertEquals(8000, scalar("SELECT count(*) FROM form_attribute").toInt())
		val elapsed = measureTime { psql("$preview\n$execute").succeeds() }
		println("Preprod cleanup local PostgreSQL 18 benchmark: 1000 forms, 3000 revisions, 8000 unique attributes, 4 KiB component content per revision, 3000 translation revisions and publications; retaining 50% of forms and associated rows: $elapsed. Synthetic local result, not a live-scale guarantee.")
		assertTrue(elapsed.inWholeSeconds < 180, "Cleanup exceeded transaction budget: $elapsed")
		mapOf("form" to 500, "form_revision" to 1500, "form_attribute" to 4000, "form_translation" to 500,
			"form_translation_revision" to 1500, "form_publication" to 1500, "published_form_translation" to 1500,
			"published_form_translation_revision" to 1500).forEach { (table, expected) ->
			assertEquals(expected, scalar("SELECT count(*) FROM $table").toInt(), table)
		}
	}

	private val preview = "\\i scripts/preprod-cleanup/preview.sql"
	private val execute = "\\i scripts/preprod-cleanup/execute.sql"

	private fun connection(databaseName: String = database): Connection =
		postgres.getDatabase(postgres.userName, databaseName).connection

	private fun form(path: String, test: Boolean = false, locked: Boolean = false, deleted: Boolean = false,
		published: Boolean = false, databaseName: String = database): Long = connection(databaseName).use { connection ->
		val id = insert(connection, "INSERT INTO form(path, created_by, lock, deleted_at) VALUES (?, 'fixture', ?::jsonb, CASE WHEN ? THEN now() END) RETURNING id",
			path, if (locked) "{}" else null, deleted)
		val components = insert(connection, "INSERT INTO form_attribute(name, value) VALUES ('components', '[]') RETURNING id")
		val properties = insert(connection, "INSERT INTO form_attribute(name, value) VALUES ('properties', ?::jsonb) RETURNING id", "{\"isTestForm\":$test}")
		val intro = insert(connection, "INSERT INTO form_attribute(name, value) VALUES ('introPage', '{}') RETURNING id")
		val revisions = (1..2).map { revision ->
			insert(connection, "INSERT INTO form_revision(form_id, revision, title, created_by, components_id, properties_id, intro_page_id) VALUES (?, ?, 'Fixture', 'fixture', ?, ?, ?) RETURNING id",
				id, revision, components, properties, intro)
		}
		val translation = insert(connection, "INSERT INTO form_translation(form_id, key) VALUES (?, 'fixture') RETURNING id", id)
		val translationRevisions = (1..2).map { revision ->
			insert(connection, "INSERT INTO form_translation_revision(form_translation_id, revision, nb, created_by) VALUES (?, ?, 'Fixture', 'fixture') RETURNING id", translation, revision)
		}
		if (published) {
			// Named guard databases intentionally have no shared fixture yet.
			if (databaseName != database) connection.createStatement().execute(GLOBAL_FIXTURE)
			val globalSnapshot = connection.createStatement().executeQuery("SELECT id FROM published_global_translation LIMIT 1").use { it.next(); it.getLong(1) }
			revisions.forEachIndexed { index, revision ->
				val snapshot = insert(connection, "INSERT INTO published_form_translation(form_id, created_by) VALUES (?, 'fixture') RETURNING id", id)
				connection.prepareStatement("INSERT INTO published_form_translation_revision VALUES (?, ?)").use {
					it.setLong(1, snapshot); it.setLong(2, translationRevisions[index]); it.executeUpdate()
				}
				insert(connection, "INSERT INTO form_publication(form_id, form_revision_id, published_form_translation_id, published_global_translation_id, created_by) VALUES (?, ?, ?, ?, 'fixture') RETURNING id", id, revision, snapshot, globalSnapshot)
			}
		}
		id
	}

	private fun insert(connection: Connection, sql: String, vararg parameters: Any?): Long =
		connection.prepareStatement(sql).use { statement ->
			parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
			statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
		}

	private fun scalar(sql: String, databaseName: String = database): String = connection(databaseName).use { connection ->
		connection.createStatement().use { it.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } }
	}

	private fun paths(): List<String> = connection().use { connection ->
		connection.createStatement().use { it.executeQuery("SELECT path FROM form ORDER BY path").use { rows ->
			buildList { while (rows.next()) add(rows.getString(1)) }
		} }
	}

	private fun snapshot(databaseName: String = database): Map<String, String> = tables.associateWith { table ->
		scalar("SELECT coalesce(jsonb_agg(row_data ORDER BY row_data::text), '[]'::jsonb)::text FROM (SELECT to_jsonb(t) row_data FROM $table t) rows", databaseName)
	}

	private fun preservedFormSnapshot(id: Long): Map<String, String> = mapOf(
		"form" to "id = $id",
		"form_revision" to "form_id = $id",
		"form_translation" to "form_id = $id",
		"published_form_translation" to "form_id = $id",
		"form_publication" to "form_id = $id",
		"form_attribute" to "EXISTS (SELECT 1 FROM form_revision r WHERE r.form_id = $id AND t.id IN (r.components_id, r.properties_id, r.intro_page_id))",
		"form_translation_revision" to "form_translation_id IN (SELECT id FROM form_translation WHERE form_id = $id)",
		"published_form_translation_revision" to "published_form_translation_id IN (SELECT id FROM published_form_translation WHERE form_id = $id)",
	).mapValues { (table, predicate) -> scalar("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM $table t WHERE $predicate") }

	private data class RunningPsql(val process: Process, val output: Path, val driver: Path)
	private data class Result(val exitCode: Int, val output: String) {
		fun succeeds() {
			assertEquals(0, exitCode, output)
			assertTrue(!output.contains("ERROR:"), output)
		}
		fun fails() {
			assertTrue(exitCode != 0, "psql unexpectedly succeeded:\n$output")
			assertTrue(!output.contains("TEST_DRIVER_COMPLETED"), "psql continued after an error:\n$output")
		}
	}

	private fun startPsql(sql: String, confirmation: String = "DELETE preprod and preprod-alt\n", databaseName: String = database, stopOnError: Boolean = true): RunningPsql {
		val driver = Files.createTempFile(temporaryDirectory, "cleanup-", ".sql")
		val output = Files.createTempFile(temporaryDirectory, "psql-", ".log")
		Files.writeString(driver, sql + "\n\\echo TEST_DRIVER_COMPLETED\n")
		val builder = ProcessBuilder("psql", "-X", "-v", "ON_ERROR_STOP=${if (stopOnError) 1 else 0}", "--no-password", "-h", postgres.host, "-p", postgres.port.toString(),
			"-U", postgres.userName, "-d", databaseName, "-f", driver.toString())
			.redirectErrorStream(true).redirectOutput(output.toFile())
		builder.environment().apply {
			keys.filter { it.startsWith("PG") }.toList().forEach { remove(it) }
			put("PGPASSWORD", postgres.password)
			put("PGCONNECT_TIMEOUT", "5")
		}
		val process = builder.start()
		process.outputStream.bufferedWriter().use { it.write(confirmation) }
		return RunningPsql(process, output, driver)
	}

	private fun finish(running: RunningPsql): Result {
		if (!running.process.waitFor(190, TimeUnit.SECONDS)) {
			running.process.destroyForcibly().waitFor()
			error("psql exceeded transaction budget:\n${Files.readString(running.output)}")
		}
		return Result(running.process.exitValue(), Files.readString(running.output))
	}

	private fun psql(sql: String, confirmation: String = "DELETE preprod and preprod-alt\n", databaseName: String = database, stopOnError: Boolean = true): Result =
		finish(startPsql(sql, confirmation, databaseName, stopOnError))

	private fun awaitOutput(running: RunningPsql, marker: String) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (!Files.readString(running.output).contains(marker) && System.nanoTime() < deadline && running.process.isAlive) Thread.sleep(20)
		assertTrue(Files.readString(running.output).contains(marker), Files.readString(running.output))
	}

	companion object {
		private val GLOBAL_FIXTURE = """
			INSERT INTO global_translation(key, tag) VALUES ('fixture-global', 'standard');
			INSERT INTO global_translation_revision(global_translation_id, revision, nb, created_by)
			SELECT id, 1, 'Fixture', 'fixture' FROM global_translation;
			INSERT INTO published_global_translation(created_by) VALUES ('fixture');
			INSERT INTO published_global_translation_revision
			SELECT p.id, r.id FROM published_global_translation p CROSS JOIN global_translation_revision r;
			INSERT INTO recipient(recipient_id, name, po_box_address, postal_code, postal_name, created_by, changed_by)
			VALUES ('fixture', 'Fixture', 'Fixture', '0000', 'Fixture', 'fixture', 'fixture');
		""".trimIndent()
	}
}
