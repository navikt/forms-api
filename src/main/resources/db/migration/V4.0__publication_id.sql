ALTER TABLE form_publication
	ADD COLUMN publication_id UUID DEFAULT uuidv7();

UPDATE form_publication
SET publication_id = DEFAULT
WHERE publication_id IS NULL;

ALTER TABLE form_publication
	ALTER COLUMN publication_id SET NOT NULL;

ALTER TABLE form_publication
	ADD CONSTRAINT uk_form_publication_publication_id UNIQUE (publication_id);

DROP VIEW form_view;

CREATE VIEW form_view AS
SELECT DISTINCT ON (f.id) f.id,
	f.path,
	f.skjemanummer,
	f.lock,
	f.deleted_at,
	f.deleted_by,
	fr.created_at AS changed_at,
	fr.created_by AS changed_by,
	fr.revision,
	fr.title,
	fa_prop.value AS properties,
	fr.id AS current_rev_id,
	fp.status AS publication_status,
	fp.created_at AS published_at,
	fp.created_by AS published_by,
	fp.publication_id,
	fp.form_revision_id AS published_rev_id
FROM form f
	JOIN form_revision fr ON f.id = fr.form_id
	JOIN form_attribute fa_prop ON fr.properties_id = fa_prop.id
	LEFT JOIN form_publication fp ON f.id = fp.form_id
ORDER BY f.id, fr.revision DESC, fp.created_at DESC, fp.id DESC;
