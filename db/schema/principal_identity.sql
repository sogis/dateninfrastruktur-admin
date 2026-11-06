CREATE FUNCTION di_ops.protect_principal_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous jsonb; upcoming jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 previous:=to_jsonb(OLD)-ARRAY['t_lastchange','t_createdate','t_user','row_version','aactive','displayname'];
 upcoming:=to_jsonb(NEW)-ARRAY['t_lastchange','t_createdate','t_user','row_version','aactive','displayname'];
 IF previous IS DISTINCT FROM upcoming THEN
  RAISE EXCEPTION 'principal external identity immutable';
 END IF;
 RETURN NEW;
END $$;
ALTER FUNCTION di_ops.protect_principal_identity() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.protect_principal_identity() FROM PUBLIC;
CREATE TRIGGER di_protect_delivery BEFORE UPDATE OR DELETE ON di_management.principal
 FOR EACH ROW EXECUTE FUNCTION di_ops.protect_principal_identity();
