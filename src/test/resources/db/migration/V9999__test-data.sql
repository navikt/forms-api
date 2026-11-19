-- Set start value for id sequences to decrease the chance of accidental green test runs due to little test data
SELECT setval('global_translation_id_seq', 21);
SELECT setval('global_translation_revision_id_seq', 1591);
