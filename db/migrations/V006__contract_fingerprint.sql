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
