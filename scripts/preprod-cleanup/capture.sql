-- Shared by preview and execute. Only session-local tables are modified.
-- 1. Clear the previous capture and reject uncertain form data.
DELETE FROM pg_temp.cleanup_current_forms;

DO $validate$
BEGIN
    IF EXISTS (SELECT 1 FROM public.form WHERE path IS NULL) THEN
        RAISE EXCEPTION 'NULL form path found; repair data before cleanup.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_temp.cleanup_inputs i,
            pg_catalog.jsonb_array_elements_text(i.keep_paths) p(path)
        WHERE NOT EXISTS (SELECT 1 FROM public.form f WHERE f.path = p.path)
    ) THEN
        RAISE EXCEPTION 'Unknown keep_paths entry; correct inputs and run preview again.';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM public.form f
        LEFT JOIN LATERAL (
            SELECT r.properties_id FROM public.form_revision r
            WHERE r.form_id = f.id ORDER BY r.revision DESC LIMIT 1
        ) latest ON true
        LEFT JOIN public.form_attribute a ON a.id = latest.properties_id
        WHERE a.id IS NULL OR a.name <> 'properties'
            OR pg_catalog.jsonb_typeof(a.value) IS DISTINCT FROM 'object'
            OR (a.value ? 'isTestForm'
                AND pg_catalog.jsonb_typeof(a.value -> 'isTestForm') IS DISTINCT FROM 'boolean')
    ) THEN
        RAISE EXCEPTION 'Missing or malformed latest properties/isTestForm; repair data before cleanup.';
    END IF;
END
$validate$;

-- 2. Classify every form. Any matching keep rule preserves the whole form.
INSERT INTO pg_temp.cleanup_current_forms
    (id, path, latest_revision_id, is_test, is_locked, is_deleted, explicit_keep, keep)
SELECT f.id, f.path, latest.id,
    COALESCE((a.value ->> 'isTestForm')::boolean, false),
    f.lock IS NOT NULL, f.deleted_at IS NOT NULL,
    i.keep_paths ? f.path,
    (i.keep_paths ? f.path)
        OR (i.keep_test_forms AND COALESCE((a.value ->> 'isTestForm')::boolean, false))
        OR (i.keep_locked_forms AND f.lock IS NOT NULL)
FROM public.form f
JOIN LATERAL (
    SELECT r.id, r.properties_id FROM public.form_revision r
    WHERE r.form_id = f.id ORDER BY r.revision DESC LIMIT 1
) latest ON true
JOIN public.form_attribute a ON a.id = latest.properties_id
CROSS JOIN pg_temp.cleanup_inputs i;
