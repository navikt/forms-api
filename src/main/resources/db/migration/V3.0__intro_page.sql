ALTER TABLE form_translation
	ADD COLUMN tag VARCHAR(16) NOT NULL DEFAULT 'standard';

CREATE INDEX form_translation_tag_idx ON form_translation (tag);
