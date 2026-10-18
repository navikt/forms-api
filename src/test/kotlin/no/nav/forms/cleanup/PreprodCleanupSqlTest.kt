package no.nav.forms.cleanup

import no.nav.forms.ApplicationTest
import no.nav.forms.model.LockFormRequest
import no.nav.forms.model.NewFormTranslationRequestDto
import no.nav.forms.model.UpdateFormRequest
import no.nav.forms.model.UpdateFormTranslationRequest
import no.nav.forms.model.UpdateGlobalTranslationRequest
import no.nav.forms.testutils.FormsTestdata
import no.nav.forms.testutils.createMockToken
import no.nav.forms.translations.testdata.GlobalTranslationsTestdata
import no.nav.forms.utils.toFormPath
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreprodCleanupSqlTest : ApplicationTest() {

	@Autowired
	lateinit var dataSource: PGSimpleDataSource
	@Autowired
	lateinit var postgres: PostgreSQLContainer
	private lateinit var sqlRunner: ContainerPsql
	private lateinit var authToken: String
	private val database = "forms-api-preprod-db"
	private val tables = listOf(
		"form", "form_attribute", "form_revision", "form_translation", "form_translation_revision",
		"published_form_translation", "published_form_translation_revision", "form_publication",
		"global_translation", "global_translation_revision", "published_global_translation",
		"published_global_translation_revision", "recipient",
	)

	@BeforeAll
	fun startSqlRunner() {
		sqlRunner = ContainerPsql(postgres)
		connection().use { connection ->
			connection.createStatement().use { statement ->
				listOf("forms-api-db", "other").forEach { name ->
					statement.execute("DROP DATABASE IF EXISTS \"$name\"")
					statement.execute("CREATE DATABASE \"$name\"")
				}
			}
		}
	}

	@AfterAll
	fun closeSqlRunner() {
		if (::sqlRunner.isInitialized) sqlRunner.close()
	}

	@BeforeEach
	fun prepareCleanupFixtures() {
		listOf("forms-api-db", "other").forEach { name ->
			Flyway.configure().dataSource(databaseDataSource(name))
				.locations("classpath:db/migration").cleanDisabled(false).load().apply {
					clean()
					migrate()
				}
		}
		authToken = mockOAuth2Server.createMockToken()
		val globalRequest = GlobalTranslationsTestdata.translations.getValue("required")
		val global = testFormsApi.createGlobalTranslation(globalRequest, authToken).assertSuccess().body
		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
		testFormsApi.putGlobalTranslation(global.id, global.revision!!,
			UpdateGlobalTranslationRequest(nb = "Updated fixture"), authToken).assertSuccess()
		testFormsApi.publishGlobalTranslations(authToken).assertSuccess()
	}

	private fun resetFixtures() {
		super.setup()
		prepareCleanupFixtures()
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
		assertTrue(before.getValue("recipient") != "[]", "Recipient preservation needs existing recipients")
		val preserved = preservedFormSnapshot(keep)
		val expectedAttributeCount = scalar("""
			SELECT count(*) FROM form_attribute a
			WHERE NOT EXISTS (SELECT 1 FROM form_revision r WHERE r.form_id <> $keep
			    AND a.id IN (r.components_id, r.properties_id, r.intro_page_id))
			OR EXISTS (SELECT 1 FROM form_revision r WHERE r.form_id = $keep
			    AND a.id IN (r.components_id, r.properties_id, r.intro_page_id))
		""".trimIndent()).toInt()
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
		assertEquals(expectedAttributeCount, scalar("SELECT count(*) FROM form_attribute").toInt())
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
		withPausedPreview {
			assertEquals(before, snapshot())
			testFormsApi.lockForm("remove", LockFormRequest(reason = "Review in progress"), authToken).assertSuccess()
		}.succeeds()
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
		form("changedtest", test = true)
		testFormsApi.updateForm("changedtest", 2,
			UpdateFormRequest(properties = mapOf("isTestForm" to false)), authToken).assertSuccess()
		psql("$preview\n$execute").succeeds()
		assertTrue(paths().isEmpty())
	}

	@Test
	fun `stale plans reject protection revision translation publication and identity changes`() {
		val apiMutations: List<() -> Unit> = listOf(
			{ testFormsApi.lockForm("remove", LockFormRequest(reason = "Changed protection"), authToken).assertSuccess() },
			{ testFormsApi.updateForm("remove", 2, UpdateFormRequest(properties = mapOf("isTestForm" to true)), authToken).assertSuccess() },
			{ testFormsApi.updateForm("remove", 2, UpdateFormRequest(title = "Changed revision"), authToken).assertSuccess() },
			{ testFormsApi.createFormTranslation("remove", NewFormTranslationRequestDto(key = "new key", nb = "Changed"), authToken).assertSuccess() },
			{
				val translation = testFormsApi.getFormTranslations("remove").assertSuccess().body.single()
				testFormsApi.updateFormTranslation("remove", translation.id, translation.revision!!,
					UpdateFormTranslationRequest(nb = "Changed"), authToken).assertSuccess()
			},
			{ testFormsApi.publishForm("remove", 2, authToken).assertSuccess() },
			{ testFormsApi.unpublishForm("remove", authToken).assertSuccess() },
			{ form("newform") },
		)
		apiMutations.forEachIndexed { index, mutation ->
			resetFixtures()
			form("remove", published = true)
			var afterMutation: Map<String, String> = emptyMap()
			val result = withPausedPreview(executeAfterReview = true) {
				mutation()
				afterMutation = snapshot()
			}
			result.fails()
			assertTrue(result.output.contains("Source data changed since preview"), "API mutation $index: ${result.output}")
			assertEquals(afterMutation, snapshot(), "API mutation $index")
		}
		// Historical rows and immutable snapshot contents cannot be edited through the API.
		val corruptions = listOf(
			"UPDATE public.form_revision SET title = 'changed' WHERE revision = 1",
			"UPDATE public.form_translation SET key = 'changed'",
			"UPDATE public.form_translation_revision SET nb = 'changed' WHERE global_translation_id IS NULL",
			"UPDATE public.published_form_translation SET created_by = 'changed'",
			"DELETE FROM public.published_form_translation_revision WHERE published_form_translation_id = (SELECT min(id) FROM public.published_form_translation)",
		)
		corruptions.forEach { corruption ->
			resetFixtures()
			form("remove", published = true)
			val result = psql("$preview\n$corruption;\n$execute")
			result.fails()
			assertTrue(result.output.contains("Source data changed since preview"), result.output)
			assertEquals(listOf("remove"), paths(), corruption)
			assertEquals(2, scalar("SELECT count(*) FROM form_revision").toInt(), corruption)
		}
		resetFixtures()
		val originalId = form("samepath")
		var afterReplacement: Map<String, String> = emptyMap()
		val result = withPausedPreview(executeAfterReview = true) {
			// Physical deletion is not exposed by the API; recreation uses the normal write path.
			connection().use { connection ->
				connection.createStatement().use { it.execute("""
					DELETE FROM public.form_translation_revision;
					DELETE FROM public.form_translation;
					DELETE FROM public.form_revision;
					DELETE FROM public.form;
				""".trimIndent()) }
			}
			assertTrue(form("samepath") != originalId)
			afterReplacement = snapshot()
		}
		result.fails()
		assertTrue(result.output.contains("Source data changed since preview"), result.output)
		assertEquals(listOf("samepath"), paths())
		assertEquals(afterReplacement, snapshot())
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
			val before = snapshot(name)
			listOf(preview, execute).forEach { entry ->
				val result = psql(entry, databaseName = name)
				result.fails()
				assertTrue(result.output.contains("Cleanup requires database forms-api-preprod-db"), result.output)
				assertEquals(before, snapshot(name), name)
			}
		}
	}

	@Test
	fun `initially disabled error stopping never bypasses guard inside entry scripts`() {
		listOf("forms-api-db", "other").forEach { name ->
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
				listOf(first, second).forEach { it.close() }
			}
		}
	}

	@Test
	fun `closing a running container session rolls back and releases its locks`() {
		form("remove", published = true)
		val before = snapshot()
		val running = startPsql("""
			BEGIN;
			UPDATE public.form SET lock = '{}'::jsonb WHERE path = 'remove';
			\echo SESSION_OPEN
			SELECT pg_sleep(60);
			COMMIT;
		""".trimIndent())
		try {
			awaitOutput(running, "SESSION_OPEN")
		} finally {
			running.close()
		}
		connection().use { writer ->
			writer.autoCommit = false
			writer.createStatement().use {
				it.execute("SET LOCAL lock_timeout = '500ms'")
				it.execute("UPDATE public.form SET lock = '{}'::jsonb WHERE path = 'remove'")
			}
			writer.rollback()
		}
		assertEquals(before, snapshot())
		psql(preview).succeeds()
	}

	@Test
	@Tag("volume")
	fun `representative 300 form cleanup with multiple revisions finishes within transaction budget`() {
		connection().use { connection ->
			connection.createStatement().execute("""
				INSERT INTO form(path, created_by, lock) SELECT 'benchmark-' || n, 'fixture',
				CASE WHEN n <= 150 THEN '{}'::jsonb END FROM generate_series(1, 300) n;
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
		assertEquals(900, scalar("SELECT count(*) FROM form_revision").toInt())
		assertEquals(2400, scalar("SELECT count(*) FROM form_attribute").toInt())
		val elapsed = measureTime { psql("$preview\n$execute").succeeds() }
		println("Preprod cleanup local PostgreSQL 18 benchmark: 300 forms, 900 revisions, 2400 unique attributes, 4 KiB component content per revision, 900 translation revisions and publications; retaining 50% of forms and associated rows: $elapsed. Synthetic local result, not a live-scale guarantee.")
		assertTrue(elapsed.inWholeSeconds < 180, "Cleanup exceeded transaction budget: $elapsed")
		mapOf("form" to 150, "form_revision" to 450, "form_attribute" to 1200, "form_translation" to 150,
			"form_translation_revision" to 450, "form_publication" to 450, "published_form_translation" to 450,
			"published_form_translation_revision" to 450).forEach { (table, expected) ->
			assertEquals(expected, scalar("SELECT count(*) FROM $table").toInt(), table)
		}
	}

	private val preview = "\\i scripts/preprod-cleanup/preview.sql"
	private val execute = "\\i scripts/preprod-cleanup/execute.sql"

	private fun databaseDataSource(name: String): PGSimpleDataSource = PGSimpleDataSource().apply {
		setURL(dataSource.getURL())
		user = dataSource.user
		password = dataSource.password
		databaseName = name
	}

	private fun connection(databaseName: String = database): Connection =
		databaseDataSource(databaseName).connection

	private fun form(path: String, test: Boolean = false, locked: Boolean = false, deleted: Boolean = false,
		published: Boolean = false): Long {
		val created = testFormsApi.createForm(FormsTestdata.newFormRequest(
			skjemanummer = path,
			properties = mapOf("isTestForm" to test),
			introPage = mapOf("title" to "Fixture intro"),
		), authToken).assertSuccess().body
		val formPath = created.path!!
		assertEquals(path.toFormPath(), formPath)
		val translation = testFormsApi.createFormTranslation(formPath,
			NewFormTranslationRequestDto(key = "fixture", nb = "Fixture"), authToken).assertSuccess().body
		if (published) testFormsApi.publishForm(formPath, created.revision!!, authToken).assertSuccess()
		val updated = testFormsApi.updateForm(formPath, created.revision!!,
			UpdateFormRequest(title = "Updated fixture"), authToken).assertSuccess().body
		testFormsApi.updateFormTranslation(formPath, translation.id, translation.revision!!,
			UpdateFormTranslationRequest(nb = "Updated fixture"), authToken).assertSuccess()
		if (published) {
			testFormsApi.publishForm(formPath, updated.revision!!, authToken).assertSuccess()
		}
		if (locked) testFormsApi.lockForm(formPath, LockFormRequest(reason = "Fixture lock"), authToken).assertSuccess()
		if (deleted) testFormsApi.deleteForm(formPath, updated.revision!!, authToken).assertSuccess()
		return created.id!!
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

	private fun ContainerPsql.Result.succeeds() {
		assertEquals(0, exitCode, output)
		assertTrue(!output.contains("ERROR:"), output)
	}

	private fun ContainerPsql.Result.fails() {
		assertTrue(exitCode != 0, "psql unexpectedly succeeded:\n$output")
		assertTrue(!output.contains("TEST_DRIVER_COMPLETED"), "psql continued after an error:\n$output")
	}

	private fun withPausedPreview(executeAfterReview: Boolean = false, duringReview: () -> Unit): ContainerPsql.Result =
		connection().use { gate ->
			gate.createStatement().use { it.execute("SELECT pg_advisory_lock(850021)") }
			val running = startPsql("$preview\n\\echo PREVIEW_READY\nSELECT pg_advisory_lock(850021);\n${if (executeAfterReview) execute else ""}")
			try {
				awaitOutput(running, "PREVIEW_READY")
				duringReview()
				gate.createStatement().use { it.execute("SELECT pg_advisory_unlock(850021)") }
				finish(running)
			} finally {
				running.close()
			}
		}

	private fun startPsql(sql: String, confirmation: String = "DELETE preprod and preprod-alt\n", databaseName: String = database, stopOnError: Boolean = true): ContainerPsql.Session =
		sqlRunner.start(sql + "\n\\echo TEST_DRIVER_COMPLETED\n", confirmation, databaseName, stopOnError)

	private fun finish(running: ContainerPsql.Session): ContainerPsql.Result = running.use { it.finish() }

	private fun psql(sql: String, confirmation: String = "DELETE preprod and preprod-alt\n", databaseName: String = database, stopOnError: Boolean = true): ContainerPsql.Result =
		finish(startPsql(sql, confirmation, databaseName, stopOnError))

	private fun awaitOutput(running: ContainerPsql.Session, marker: String) = running.awaitOutput(marker)
}
