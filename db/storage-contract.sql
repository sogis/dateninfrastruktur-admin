-- Current read-only runtime guard, shared by fresh setup and explicit upgrade.
CREATE OR REPLACE FUNCTION di_ops.single_storage_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision='MA-G1-1')
 AND NOT EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class t ON t.oid=a.attrelid
   JOIN pg_namespace n ON n.oid=t.relnamespace
   WHERE n.nspname='di_management' AND t.relname='distribution' AND NOT a.attisdropped
   AND a.attname IN ('storageprofile','legacyaccessurl'))
 AND EXISTS(SELECT 1 FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid
   JOIN pg_namespace n ON n.oid=t.relnamespace
   WHERE n.nspname='di_management' AND t.relname='distribution' AND i.indisunique
   AND i.indnkeyatts=1 AND pg_get_indexdef(i.indexrelid,1,true)='objectkey');
$$;
ALTER FUNCTION di_ops.single_storage_contract() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.single_storage_contract() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION di_ops.single_storage_contract() TO di_writer,di_authz;
