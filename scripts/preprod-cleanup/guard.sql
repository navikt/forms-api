\set ON_ERROR_STOP on
\set ON_ERROR_ROLLBACK off
\set ECHO none
\set VERBOSITY terse
\set SHOW_CONTEXT never

-- COMMIT inside a top-level DO is permitted only outside a caller transaction.
-- Inside BEGIN (including psql --single-transaction), it raises without committing.
-- Keep this DO as a separate statement, before any session or temporary setup.
DO $guard$
BEGIN
    IF pg_catalog.current_database() OPERATOR(pg_catalog.<>) 'forms-api-preprod-db' THEN
        RAISE EXCEPTION 'Cleanup requires database forms-api-preprod-db.';
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

SET search_path = pg_catalog, pg_temp;
SET timezone = 'UTC';
SET datestyle = 'ISO, YMD';
