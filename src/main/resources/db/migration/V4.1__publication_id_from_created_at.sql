CREATE OR REPLACE FUNCTION uuidv7_from_timestamptz(ts TIMESTAMPTZ, order_seed BIGINT)
RETURNS UUID
LANGUAGE SQL
IMMUTABLE
RETURNS NULL ON NULL INPUT
AS
$$
	SELECT (
		substr(timestamp_hex, 1, 8) || '-' ||
		substr(timestamp_hex, 9, 4) || '-' ||
		'7' || substr(order_hex, 1, 3) || '-' ||
		'8' || substr(order_hex, 4, 3) || '-' ||
		substr(order_hex, 7, 12)
	)::UUID
	FROM (
		SELECT
			lpad(to_hex(floor(extract(epoch FROM ts) * 1000)::BIGINT), 12, '0') AS timestamp_hex,
			lpad(to_hex(order_seed), 18, '0') AS order_hex
	) generated_bits;
$$;

WITH v4_migration AS (
	SELECT installed_on
	FROM flyway_schema_history
	WHERE version = '4.0'
	  AND success
	ORDER BY installed_rank DESC
	LIMIT 1
)
UPDATE form_publication fp
SET publication_id = uuidv7_from_timestamptz(fp.created_at, fp.id)
FROM v4_migration
WHERE fp.created_at < v4_migration.installed_on
  AND abs(extract(epoch FROM (uuid_extract_timestamp(fp.publication_id) - fp.created_at))) > 1;

DROP FUNCTION uuidv7_from_timestamptz(TIMESTAMPTZ, BIGINT);
