CREATE TABLE IF NOT EXISTS di_ops.contract_fingerprint (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 fingerprint text NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$')
);
ALTER TABLE di_ops.contract_fingerprint OWNER TO di_owner;
CREATE OR REPLACE FUNCTION di_ops.contract_fingerprint() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;
$$;
ALTER FUNCTION di_ops.contract_fingerprint() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.contract_fingerprint() FROM PUBLIC;
GRANT SELECT ON di_ops.contract_fingerprint TO di_audit_owner;
GRANT EXECUTE ON FUNCTION di_ops.contract_fingerprint() TO di_writer;

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
CREATE OR REPLACE FUNCTION di_ops.management_web_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT to_regclass('di_ops.command_receipt') IS NOT NULL
   AND has_table_privilege('di_writer','di_ops.command_receipt','SELECT')
   AND has_table_privilege('di_writer','di_ops.command_receipt','INSERT')
   AND has_table_privilege('di_writer','di_ops.command_receipt','UPDATE')
   AND NOT has_table_privilege('di_authz','di_ops.command_receipt','SELECT')
   AND has_table_privilege('di_audit_reader','audit.row_change','SELECT');
$$;
ALTER FUNCTION di_ops.management_web_contract() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.management_web_contract() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION di_ops.management_web_contract() TO di_writer;
