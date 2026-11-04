-- Technical immutable provenance/UUID assignment, not a second editable catalogue.
CREATE TABLE IF NOT EXISTS di_ops.legacy_origin (
 kind text NOT NULL, source_identifier text NOT NULL, target_oid uuid NOT NULL,
 source_model text NOT NULL, object_sha256 text NOT NULL CHECK(object_sha256 ~ '^[0-9a-f]{64}$'),
 source_file_sha256 text NOT NULL CHECK(source_file_sha256 ~ '^[0-9a-f]{64}$'),
 original_xml text NOT NULL, command_id uuid NOT NULL, imported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(kind,source_identifier), UNIQUE(target_oid)
);
ALTER TABLE di_ops.legacy_origin OWNER TO di_owner;
REVOKE ALL ON di_ops.legacy_origin FROM PUBLIC;
GRANT SELECT,INSERT ON di_ops.legacy_origin TO di_writer;
INSERT INTO di_ops.schema_revision(revision) VALUES('MA-D-1') ON CONFLICT DO NOTHING;
