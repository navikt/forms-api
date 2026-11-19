-- Shared target checks. No application writes or temporary tables in this file.
\set ON_ERROR_STOP on
\set ON_ERROR_ROLLBACK off
\set ECHO none
\set VERBOSITY terse
\set SHOW_CONTEXT never

-- 1. Read the expected database name. Never treat it as a connection string.
\if :{?cleanup_database}
\else
\set cleanup_database 'forms-api-preprod-db'
\endif

-- Quoted substitution passes a literal into the DO block without printing it.
SELECT pg_catalog.set_config('cleanup.expected_database', :'cleanup_database', false) AS cleanup_expected_database \gset
\unset cleanup_expected_database

-- 2. Always reject the production name. Require an exact expected-name match.
-- COMMIT inside a top-level DO is permitted only outside a caller transaction.
-- Inside BEGIN (including psql --single-transaction), it raises without committing.
-- Keep this DO separate, before any temporary DDL or cleanup approval.
DO $guard$
DECLARE
    expected_database pg_catalog.text := pg_catalog.current_setting('cleanup.expected_database');
BEGIN
    IF pg_catalog.current_database() OPERATOR(pg_catalog.=) 'forms-api-db' THEN
        RAISE EXCEPTION 'Cleanup is forbidden on the production database.';
    END IF;
    IF expected_database OPERATOR(pg_catalog.!~) '[^[:space:]]' THEN
        RAISE EXCEPTION 'cleanup_database must not be empty or whitespace.';
    END IF;
    IF expected_database OPERATOR(pg_catalog.=) 'forms-api-db' THEN
        RAISE EXCEPTION 'cleanup_database must not name the production database.';
    END IF;
    IF pg_catalog.current_database()::pg_catalog.text OPERATOR(pg_catalog.<>) expected_database THEN
        RAISE EXCEPTION 'Connected database does not match cleanup_database.';
    END IF;
    COMMIT;
END
$guard$;

\if :AUTOCOMMIT
\else
DO $guard$ BEGIN
    RAISE EXCEPTION 'Cleanup requires psql AUTOCOMMIT on and no existing transaction.';
END $guard$;
\endif

-- 3. Fix name resolution and formatting for subsequent captures.
SET search_path = pg_catalog, pg_temp;
SET timezone = 'UTC';
SET datestyle = 'ISO, YMD';
