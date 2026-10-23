-- 1. Check the target and require a completed preview in this session.
-- Error handling must be set before entering nested files.
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

-- 2. Require confirmation before taking any write-blocking locks.
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

-- 3. Start one bounded transaction and block writes in a fixed lock order.
\echo 'Starting cleanup transaction and setting timeouts.'
BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL transaction_timeout = '180s';

\echo 'Acquiring write-blocking locks.'
LOCK TABLE public.form IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_attribute IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_translation IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_translation_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.published_form_translation IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.published_form_translation_revision IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE public.form_publication IN SHARE ROW EXCLUSIVE MODE;

-- 4. Rebuild the plan under locks. Abort if anything changed after preview.
\echo 'Refreshing temporary plan. DELETE/INSERT counts below are temporary rows, not application deletions.'
\ir capture.sql
\echo 'Checking refreshed plan against approved preview.'
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

-- 5. Save candidate attributes before removing revisions.
-- All deletions use the same fixed set of form IDs.
\echo 'Collecting form_attribute candidates. DELETE/INSERT counts below are temporary rows, not application deletions.'
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

-- 6. Delete publication history, translation snapshots, translations, and revisions.
-- Foreign keys determine the order. Global translations and recipients stay.
\echo 'Deleting from public.form_publication.'
DELETE FROM public.form_publication p
USING pg_temp.cleanup_current_forms f WHERE p.form_id = f.id AND NOT f.keep;

\echo 'Deleting from public.published_form_translation_revision.'
DELETE FROM public.published_form_translation_revision r
USING public.published_form_translation p, pg_temp.cleanup_current_forms f
WHERE r.published_form_translation_id = p.id AND p.form_id = f.id AND NOT f.keep;

\echo 'Deleting from public.published_form_translation.'
DELETE FROM public.published_form_translation p
USING pg_temp.cleanup_current_forms f WHERE p.form_id = f.id AND NOT f.keep;

\echo 'Deleting from public.form_translation_revision.'
DELETE FROM public.form_translation_revision r
USING public.form_translation t, pg_temp.cleanup_current_forms f
WHERE r.form_translation_id = t.id AND t.form_id = f.id AND NOT f.keep;

\echo 'Deleting from public.form_translation.'
DELETE FROM public.form_translation t
USING pg_temp.cleanup_current_forms f WHERE t.form_id = f.id AND NOT f.keep;

\echo 'Deleting from public.form_revision.'
DELETE FROM public.form_revision r
USING pg_temp.cleanup_current_forms f WHERE r.form_id = f.id AND NOT f.keep;

-- 7. Delete only candidate attributes no longer used in any revision column.
-- Build remaining references once rather than scanning revisions per candidate.
\echo 'Deleting unreferenced candidates from public.form_attribute.'
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

-- 8. Delete the forms and commit everything together.
\echo 'Deleting from public.form.'
DELETE FROM public.form f
USING pg_temp.cleanup_current_forms target WHERE f.id = target.id AND NOT target.keep;

-- Approval is single use. A rollback restores it, but a new preview is recommended.
\echo 'Invalidating preview approval (temporary rows only).'
DELETE FROM pg_temp.cleanup_ready;
\echo 'Committing cleanup transaction.'
COMMIT;
\echo 'Cleanup committed. Run preview.sql again before any further execution.'
