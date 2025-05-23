CREATE TABLE form_attribute
(
	id    BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
	name  VARCHAR(16) NOT NULL,
	value JSONB       NOT NULL
);

CREATE INDEX form_attribute_name_idx ON form_attribute (name);

ALTER TABLE form_revision
	ADD COLUMN intro_page_id BIGINT,
	ADD CONSTRAINT fk_form_revision_intro_page_form_attribute
		FOREIGN KEY (intro_page_id)
			REFERENCES form_attribute (id);

ALTER TABLE form_translation
	ADD COLUMN tag VARCHAR(16) NOT NULL DEFAULT 'standard';

CREATE INDEX form_translation_tag_idx ON form_translation (tag);
