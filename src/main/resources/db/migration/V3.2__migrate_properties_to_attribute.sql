-- Migration script to move properties from form_revision to form_attribute
ALTER TABLE form_revision
	ADD COLUMN properties_id BIGINT,
	ADD CONSTRAINT fk_form_revision_properties_form_attribute
		FOREIGN KEY (properties_id)
			REFERENCES form_attribute (id);

ALTER TABLE form_attribute
	ADD COLUMN form_revision_id BIGINT;

INSERT INTO form_attribute (name, value, form_revision_id)
SELECT 'properties', fr.properties, fr.id
FROM form_revision fr;

UPDATE form_revision
SET properties_id = fa.id FROM form_attribute fa
WHERE fa.name = 'properties'
	AND fa.form_revision_id = form_revision.id;

ALTER TABLE form_attribute
  DROP COLUMN form_revision_id;

DROP VIEW form_view;

CREATE VIEW form_view AS
SELECT DISTINCT ON (f.id) f.id, f.path, f.skjemanummer, f.lock, f.deleted_at, f.deleted_by, fr.created_at AS changed_at, fr.created_by AS changed_by, fr.revision, fr.title, fa_prop.value as properties, fr.id as current_rev_id, fp.status as publication_status, fp.created_at AS published_at, fp.created_by AS published_by, fp.form_revision_id as published_rev_id
	FROM form f
	JOIN form_revision fr ON f.id = fr.form_id
	JOIN form_attribute fa_prop ON fr.properties_id = fa_prop.id
	LEFT JOIN form_publication fp ON f.id = fp.form_id
	ORDER BY f.id, fr.revision DESC, fp.created_at DESC;

ALTER TABLE form_revision
  DROP COLUMN properties,
	ALTER COLUMN properties_id SET NOT NULL;
