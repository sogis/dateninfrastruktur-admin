CREATE TABLE IF NOT EXISTS di_ops.command_receipt (
 actor_issuer text NOT NULL, actor_subject text NOT NULL, command_id uuid NOT NULL,
 action_code text NOT NULL, payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 result_ref uuid, result_code text, completed_at timestamptz,
 PRIMARY KEY(actor_issuer,actor_subject,command_id)
);
ALTER TABLE di_ops.command_receipt OWNER TO di_owner;
REVOKE ALL ON di_ops.command_receipt FROM PUBLIC;
GRANT USAGE ON SCHEMA di_ops TO di_writer;
GRANT SELECT,INSERT,UPDATE ON di_ops.command_receipt TO di_writer;
GRANT USAGE ON SCHEMA audit TO di_audit_reader;
GRANT SELECT ON audit.row_change TO di_audit_reader;
INSERT INTO di_ops.schema_revision(revision) VALUES('MA-C-1') ON CONFLICT DO NOTHING;
CREATE OR REPLACE FUNCTION di_ops.management_web_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision='MA-C-1')
   AND to_regclass('di_ops.command_receipt') IS NOT NULL
   AND has_table_privilege('di_writer','di_ops.command_receipt','SELECT')
   AND has_table_privilege('di_writer','di_ops.command_receipt','INSERT')
   AND has_table_privilege('di_writer','di_ops.command_receipt','UPDATE')
   AND NOT has_table_privilege('di_authz','di_ops.command_receipt','SELECT')
   AND has_table_privilege('di_audit_reader','audit.row_change','SELECT');
$$;
ALTER FUNCTION di_ops.management_web_contract() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.management_web_contract() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION di_ops.management_web_contract() TO di_writer;
