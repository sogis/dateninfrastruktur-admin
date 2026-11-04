-- Explicit MA-F technical migration. Models, physical tables and all audit
-- triggers remain unchanged. Only the principal business guard is specialized.
CREATE OR REPLACE FUNCTION di_ops.protect_principal_identity() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous jsonb; upcoming jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 previous:=to_jsonb(OLD)-ARRAY['t_lastchange','t_createdate','t_user','row_version'];
 upcoming:=to_jsonb(NEW)-ARRAY['t_lastchange','t_createdate','t_user','row_version'];
 IF previous-ARRAY['aactive','displayname'] IS DISTINCT FROM upcoming-ARRAY['aactive','displayname'] THEN
  IF session_user='di_migrator'
   AND current_setting('di.action',true)='operator.migrate_identity'
   AND current_setting('di.actor_kind',true)='service'
   AND current_setting('di.actor_issuer',true)='urn:di:migration'
   AND nullif(current_setting('di.migration_operation',true),'') IS NOT NULL
   AND current_setting('di.migration_operation')::uuid=current_setting('di.command_id')::uuid
   AND current_setting('di.actor_subject')=current_setting('di.migration_operation')
   AND NEW.aissuer=current_setting('di.migration_target_issuer',true)
   AND NEW.aissuer ~ '^http://localhost:[0-9]+/realms/dateninfrastruktur$'
   AND OLD.akind='user' AND NEW.akind='user'
   AND OLD.asubject=NEW.asubject
   AND previous-'aissuer'=upcoming-'aissuer'
  THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'principal external identity immutable';
 END IF;
 RETURN NEW;
END $$;
ALTER FUNCTION di_ops.protect_principal_identity() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.protect_principal_identity() FROM PUBLIC;
LOCK TABLE di_management.principal IN SHARE ROW EXCLUSIVE MODE;
DROP TRIGGER di_protect_delivery ON di_management.principal;
CREATE TRIGGER di_protect_delivery BEFORE UPDATE OR DELETE ON di_management.principal
 FOR EACH ROW EXECUTE FUNCTION di_ops.protect_principal_identity();
INSERT INTO di_ops.schema_revision(revision) VALUES('MA-F2-1');
