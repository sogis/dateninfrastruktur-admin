CREATE OR REPLACE FUNCTION di_ops.touch_aggregate() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE parent_oid text; source_row jsonb;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(OLD)-ARRAY['row_version','t_user','t_lastchange','t_createdate'])=(to_jsonb(NEW)-ARRAY['row_version','t_user','t_lastchange','t_createdate']) THEN RETURN NULL; END IF;
 IF TG_OP='DELETE' THEN source_row:=to_jsonb(OLD); ELSE source_row:=to_jsonb(NEW); END IF;
 parent_oid:=audit.dataset_oid(TG_TABLE_NAME::name,source_row);
 IF parent_oid IS NOT NULL THEN
  UPDATE di_management.dataset SET row_version=row_version+1 WHERE t_ili_tid=parent_oid::uuid;
 END IF;
 RETURN NULL;
END $$;
ALTER FUNCTION di_ops.touch_aggregate() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.touch_aggregate() FROM PUBLIC;
GRANT USAGE ON SCHEMA audit TO di_owner;
GRANT EXECUTE ON FUNCTION audit.dataset_oid(name,jsonb) TO di_owner;
