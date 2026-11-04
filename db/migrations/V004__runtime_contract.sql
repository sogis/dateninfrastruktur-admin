CREATE OR REPLACE FUNCTION di_ops.runtime_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision='MA-B-2')
 AND (SELECT count(*) FROM audit.table_policy)=16
 AND NOT EXISTS (
  SELECT 1 FROM audit.table_policy policy
  CROSS JOIN (VALUES ('di_audit_stamp','stamp_row',31),('di_audit_log','log_row_change',29)) expected(trigger_name,function_name,type)
  WHERE NOT EXISTS (
   SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
   JOIN pg_namespace s ON s.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
   JOIN pg_namespace n ON n.oid=p.pronamespace
   WHERE s.nspname='di_management' AND c.relname=policy.table_name
    AND t.tgname=expected.trigger_name AND p.proname=expected.function_name
    AND n.nspname='audit' AND t.tgenabled='O' AND t.tgtype=expected.type));
$$;
ALTER FUNCTION di_ops.runtime_contract() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.runtime_contract() FROM PUBLIC;
GRANT USAGE ON SCHEMA di_ops TO di_writer,di_authz,di_audit_owner;
GRANT SELECT ON di_ops.schema_revision TO di_audit_owner;
GRANT EXECUTE ON FUNCTION di_ops.runtime_contract() TO di_writer,di_authz;
