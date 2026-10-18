-- Set error handling here, before entering any nested include.
\set ON_ERROR_STOP on
\set ON_ERROR_ROLLBACK off
\ir guard.sql

\if :{?keep_paths}
\else
\set keep_paths '[]'
\endif
\if :{?keep_test_forms}
\else
\set keep_test_forms true
\endif
\if :{?keep_locked_forms}
\else
\set keep_locked_forms true
\endif

-- Invalidate any older approval before validating new inputs. No source writes.
DROP TABLE IF EXISTS pg_temp.cleanup_ready;
DROP TABLE IF EXISTS pg_temp.cleanup_raw_inputs;
DROP TABLE IF EXISTS pg_temp.cleanup_inputs;
DROP TABLE IF EXISTS pg_temp.cleanup_current_forms;
DROP TABLE IF EXISTS pg_temp.cleanup_approved_forms;
DROP TABLE IF EXISTS pg_temp.cleanup_current_fingerprints;
DROP TABLE IF EXISTS pg_temp.cleanup_approved_fingerprints;
DROP TABLE IF EXISTS pg_temp.cleanup_candidate_attributes;

CREATE TEMP TABLE cleanup_ready (ready boolean NOT NULL CHECK (ready)) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_raw_inputs (paths text, tests text, locked text) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_inputs (
    keep_paths jsonb NOT NULL,
    keep_test_forms boolean NOT NULL,
    keep_locked_forms boolean NOT NULL
) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_current_forms (
    id bigint PRIMARY KEY, path text NOT NULL, latest_revision_id bigint NOT NULL,
    is_test boolean NOT NULL, is_locked boolean NOT NULL, is_deleted boolean NOT NULL,
    explicit_keep boolean NOT NULL, keep boolean NOT NULL
) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_approved_forms
    (LIKE pg_temp.cleanup_current_forms INCLUDING ALL) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_current_fingerprints (
    source_table text NOT NULL, row_key text NOT NULL, fingerprint text NOT NULL,
    PRIMARY KEY (source_table, row_key)
) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_approved_fingerprints
    (LIKE pg_temp.cleanup_current_fingerprints INCLUDING ALL) ON COMMIT PRESERVE ROWS;
CREATE TEMP TABLE cleanup_candidate_attributes (id bigint PRIMARY KEY) ON COMMIT PRESERVE ROWS;

INSERT INTO pg_temp.cleanup_raw_inputs VALUES (:'keep_paths', :'keep_test_forms', :'keep_locked_forms');
DO $inputs$
DECLARE
    supplied pg_temp.cleanup_raw_inputs%ROWTYPE;
    paths jsonb;
BEGIN
    SELECT * INTO STRICT supplied FROM pg_temp.cleanup_raw_inputs;
    IF NOT pg_catalog.pg_input_is_valid(supplied.paths, 'jsonb') THEN
        RAISE EXCEPTION 'keep_paths must be a JSON array of nonempty, unique strings.';
    END IF;
    paths := supplied.paths::jsonb;
    IF pg_catalog.jsonb_typeof(paths) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'keep_paths must be a JSON array.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.jsonb_array_elements(paths) p(value)
        WHERE pg_catalog.jsonb_typeof(p.value) IS DISTINCT FROM 'string'
            OR pg_catalog.btrim(p.value #>> '{}') = ''
    ) THEN
        RAISE EXCEPTION 'keep_paths entries must be nonempty strings.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_catalog.jsonb_array_elements_text(paths) p(path)
        GROUP BY p.path HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate keep_paths entry.';
    END IF;
    IF NOT pg_catalog.pg_input_is_valid(supplied.tests, 'boolean')
        OR NOT pg_catalog.pg_input_is_valid(supplied.locked, 'boolean') THEN
        RAISE EXCEPTION 'keep_test_forms and keep_locked_forms must be booleans.';
    END IF;
    INSERT INTO pg_temp.cleanup_inputs VALUES (paths, supplied.tests::boolean, supplied.locked::boolean);
END
$inputs$;
DELETE FROM pg_temp.cleanup_raw_inputs;

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL transaction_timeout = '180s';
\ir capture.sql
INSERT INTO pg_temp.cleanup_approved_forms SELECT * FROM pg_temp.cleanup_current_forms;
INSERT INTO pg_temp.cleanup_approved_fingerprints SELECT * FROM pg_temp.cleanup_current_fingerprints;
INSERT INTO pg_temp.cleanup_ready VALUES (true);
COMMIT;

\echo 'Preview only. Execution permanently affects BOTH preprod and preprod-alt.'
SELECT keep_paths, keep_test_forms, keep_locked_forms FROM pg_temp.cleanup_inputs;
SELECT source_table, count(*) AS rows
FROM pg_temp.cleanup_approved_fingerprints GROUP BY source_table ORDER BY source_table;
SELECT 'DELETE' AS action, count(*) AS forms
FROM pg_temp.cleanup_approved_forms WHERE NOT keep
UNION ALL
SELECT 'KEEP', count(*) FROM pg_temp.cleanup_approved_forms WHERE keep;
SELECT CASE WHEN keep THEN 'KEEP' ELSE 'DELETE' END AS action,
    id, path, latest_revision_id, is_test, is_locked, is_deleted, explicit_keep
FROM pg_temp.cleanup_approved_forms ORDER BY keep, path;
\echo 'Review this list, then include execute.sql in this same psql session.'
