-- Set error handling here, before entering any nested include.
\set ON_ERROR_STOP on
\set ON_ERROR_ROLLBACK off
\ir guard.sql

DO $approval$
BEGIN
    IF pg_catalog.to_regclass('pg_temp.cleanup_ready') IS NULL THEN
        RAISE EXCEPTION 'Run preview.sql successfully in this session before execute.sql.';
    END IF;
    IF (SELECT count(*) FROM pg_temp.cleanup_ready WHERE ready) <> 1 THEN
        RAISE EXCEPTION 'No approved preview; run preview.sql again.';
    END IF;
END
$approval$;

\echo 'Irreversible deletion after COMMIT. BOTH preprod and preprod-alt are affected.'
\echo 'Confirmation uses the frozen preview inputs, not current psql input variables.'
\prompt 'Type DELETE preprod and preprod-alt to continue: ' cleanup_confirmation
SELECT :'cleanup_confirmation' = 'DELETE preprod and preprod-alt' AS cleanup_confirmed \gset
\unset cleanup_confirmation
\if :cleanup_confirmed
\else
DO $confirmation$ BEGIN RAISE EXCEPTION 'Confirmation rejected; nothing deleted.'; END $confirmation$;
\endif
\unset cleanup_confirmed

BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL transaction_timeout = '180s';

-- Order is fixed for every cleanup. No write-blocking lock is held at the prompt.
LOCK TABLE public.form IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_attribute IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_translation IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_translation_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.published_form_translation IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.published_form_translation_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_publication IN SHARE ROW EXCLUSIVE MODE;

\ir capture.sql
DO $compare$
BEGIN
    IF EXISTS (
        (SELECT * FROM pg_temp.cleanup_current_fingerprints
         EXCEPT SELECT * FROM pg_temp.cleanup_approved_fingerprints)
        UNION ALL
        (SELECT * FROM pg_temp.cleanup_approved_fingerprints
         EXCEPT SELECT * FROM pg_temp.cleanup_current_fingerprints)
    ) OR EXISTS (
        (SELECT * FROM pg_temp.cleanup_current_forms
         EXCEPT SELECT * FROM pg_temp.cleanup_approved_forms)
        UNION ALL
        (SELECT * FROM pg_temp.cleanup_approved_forms
         EXCEPT SELECT * FROM pg_temp.cleanup_current_forms)
    ) THEN
        RAISE EXCEPTION 'Source data changed since preview; ROLLBACK and run preview.sql again.';
    END IF;
END
$compare$;

-- The locked current_forms IDs are the fixed target set for every DELETE.
DELETE FROM pg_temp.cleanup_candidate_attributes;
INSERT INTO pg_temp.cleanup_candidate_attributes (id)
SELECT r.components_id FROM public.form_revision r
JOIN pg_temp.cleanup_current_forms f ON f.id = r.form_id WHERE NOT f.keep
UNION
SELECT r.properties_id FROM public.form_revision r
JOIN pg_temp.cleanup_current_forms f ON f.id = r.form_id WHERE NOT f.keep
UNION
SELECT r.intro_page_id FROM public.form_revision r
JOIN pg_temp.cleanup_current_forms f ON f.id = r.form_id WHERE NOT f.keep AND r.intro_page_id IS NOT NULL;

DELETE FROM public.form_publication p
USING pg_temp.cleanup_current_forms f WHERE p.form_id = f.id AND NOT f.keep;

DELETE FROM public.published_form_translation_revision r
USING public.published_form_translation p, pg_temp.cleanup_current_forms f
WHERE r.published_form_translation_id = p.id AND p.form_id = f.id AND NOT f.keep;

DELETE FROM public.published_form_translation p
USING pg_temp.cleanup_current_forms f WHERE p.form_id = f.id AND NOT f.keep;

DELETE FROM public.form_translation_revision r
USING public.form_translation t, pg_temp.cleanup_current_forms f
WHERE r.form_translation_id = t.id AND t.form_id = f.id AND NOT f.keep;

DELETE FROM public.form_translation t
USING pg_temp.cleanup_current_forms f WHERE t.form_id = f.id AND NOT f.keep;

DELETE FROM public.form_revision r
USING pg_temp.cleanup_current_forms f WHERE r.form_id = f.id AND NOT f.keep;

-- Build remaining references once rather than scanning revisions per candidate.
WITH referenced_attributes AS MATERIALIZED (
    SELECT components_id AS id FROM public.form_revision
    UNION
    SELECT properties_id FROM public.form_revision
    UNION
    SELECT intro_page_id FROM public.form_revision WHERE intro_page_id IS NOT NULL
)
DELETE FROM public.form_attribute a
USING pg_temp.cleanup_candidate_attributes candidate
WHERE a.id = candidate.id AND NOT EXISTS (
    SELECT 1 FROM referenced_attributes referenced WHERE referenced.id = a.id
);

DELETE FROM public.form f
USING pg_temp.cleanup_current_forms target WHERE f.id = target.id AND NOT target.keep;

-- Approval is single use. A rollback restores it, but a new preview is recommended.
DELETE FROM pg_temp.cleanup_ready;
COMMIT;
\echo 'Cleanup committed. Run preview.sql again before any further execution.'
