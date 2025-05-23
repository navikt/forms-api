ALTER TABLE form_revision
	ADD COLUMN components_id BIGINT,
	ADD CONSTRAINT fk_form_revision_components_form_attribute
		FOREIGN KEY (components_id)
			REFERENCES form_attribute (id);

ALTER TABLE form_attribute
	ADD COLUMN form_revision_id BIGINT;

INSERT INTO form_attribute (name, value, form_revision_id)
SELECT 'components', frc.value, fr.id
FROM form_revision fr
JOIN form_revision_components frc ON fr.form_revision_components_id = frc.id;

UPDATE form_revision
SET components_id = fa.id FROM form_attribute fa
WHERE fa.name = 'components'
	AND fa.form_revision_id = form_revision.id;

ALTER TABLE form_attribute
  DROP COLUMN form_revision_id;

ALTER TABLE form_revision
	DROP COLUMN form_revision_components_id,
	ALTER COLUMN components_id SET NOT NULL;

DROP TABLE form_revision_components;
