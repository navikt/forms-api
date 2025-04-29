DROP VIEW form_view;

CREATE VIEW form_view AS
SELECT DISTINCT ON (f.id) f.id, f.path, f.skjemanummer, f.lock, f.deleted_at, f.deleted_by, fr.created_at AS changed_at, fr.created_by AS changed_by, fr.revision, fr.title, fr.properties, fr.id as current_rev_id, fp.status as publication_status, fp.created_at AS published_at, fp.created_by AS published_by, fp.form_revision_id as published_rev_id
	FROM form f
	JOIN form_revision fr ON f.id = fr.form_id
	LEFT JOIN form_publication fp ON f.id = fp.form_id
	ORDER BY f.id, fr.revision DESC, fp.created_at DESC;
