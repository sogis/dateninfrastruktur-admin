-- Explicit setup only. Application runtime never owns or installs schema objects.
ALTER SCHEMA di_management OWNER TO di_owner;
CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION di_audit_owner;
CREATE SCHEMA IF NOT EXISTS api_public AUTHORIZATION di_owner;
CREATE SCHEMA IF NOT EXISTS di_ops AUTHORIZATION di_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA di_management,audit,api_public,di_ops FROM PUBLIC;
CREATE TABLE IF NOT EXISTS di_ops.schema_revision(revision text PRIMARY KEY, installed_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE IF NOT EXISTS audit.row_change (
 change_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), transaction_id text NOT NULL,
 request_id uuid NOT NULL, command_id uuid NOT NULL, action_code text NOT NULL,
 actor_kind text NOT NULL, actor_issuer text NOT NULL, actor_subject text NOT NULL,
 actor_principal_oid text, database_user text NOT NULL, schema_name text NOT NULL,
 table_name text NOT NULL, model_element text NOT NULL, schema_revision text NOT NULL,
 object_oid text, technical_key jsonb NOT NULL, aggregate_dataset_oid text,
 operation char(1) NOT NULL CHECK(operation IN ('I','U','D')),
 old_values jsonb, new_values jsonb, changed_columns text[] NOT NULL
);
ALTER TABLE audit.row_change OWNER TO di_audit_owner;
CREATE INDEX IF NOT EXISTS row_change_dataset_time ON audit.row_change(aggregate_dataset_oid,recorded_at);
CREATE INDEX IF NOT EXISTS row_change_command ON audit.row_change(command_id);
CREATE INDEX IF NOT EXISTS row_change_object ON audit.row_change(object_oid);
CREATE TABLE IF NOT EXISTS audit.table_policy (
 table_name name PRIMARY KEY, model_elements text[] NOT NULL, redacted_columns text[] NOT NULL,
 dataset_path jsonb NOT NULL, protection text NOT NULL
);
ALTER TABLE audit.table_policy OWNER TO di_audit_owner;
CREATE OR REPLACE FUNCTION audit.require_context() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF nullif(current_setting('di.actor_issuer',true),'') IS NULL OR nullif(current_setting('di.actor_subject',true),'') IS NULL
 OR coalesce(current_setting('di.actor_kind',true),'') NOT IN ('user','service')
 OR nullif(current_setting('di.action',true),'') IS NULL THEN RAISE EXCEPTION 'verified audit context required'; END IF;
 IF nullif(current_setting('di.request_id',true),'') IS NULL OR nullif(current_setting('di.command_id',true),'') IS NULL
 THEN RAISE EXCEPTION 'audit correlation required'; END IF;
 PERFORM current_setting('di.request_id')::uuid,current_setting('di.command_id')::uuid;
END $$;
CREATE OR REPLACE FUNCTION audit.dataset_oid(source_table name, source_row jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE path jsonb; step jsonb; value jsonb := source_row; parent_key text;
BEGIN
 SELECT dataset_path INTO STRICT path FROM audit.table_policy WHERE table_name=source_table;
 IF jsonb_array_length(path)=0 THEN RETURN NULL; END IF;
 FOR step IN SELECT jsonb_array_elements(path) LOOP
  parent_key:=value->>(step->>'column');
  EXECUTE format('SELECT to_jsonb(t) FROM di_management.%I t WHERE t_id=$1',step->>'table')
    INTO value USING parent_key::bigint;
  IF value IS NULL THEN
   -- A deferred-FK transaction may delete the parent before its child.
   -- Earlier audit rows of THIS transaction retain that parent's identity/path.
   SELECT coalesce(new_values,old_values) INTO value FROM audit.row_change
   WHERE transaction_id=pg_current_xact_id()::text AND table_name=step->>'table'
     AND technical_key->>'t_id'=parent_key ORDER BY change_id DESC LIMIT 1;
  END IF;
  IF value IS NULL THEN RETURN NULL; END IF;
 END LOOP;
 RETURN value->>'t_ili_tid';
END $$;
CREATE OR REPLACE FUNCTION audit.stamp_row() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE before_row jsonb; after_row jsonb; policy audit.table_policy;
BEGIN
 PERFORM audit.require_context();
 SELECT * INTO STRICT policy FROM audit.table_policy WHERE table_name=TG_TABLE_NAME;
 IF TG_OP='DELETE' THEN
  IF policy.protection IN ('immutable','grant') THEN RAISE EXCEPTION 'historical object deletion forbidden'; END IF;
  RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.t_id IS DISTINCT FROM OLD.t_id OR NEW.t_ili_tid IS DISTINCT FROM OLD.t_ili_tid THEN RAISE EXCEPTION 'stable identity immutable'; END IF;
  before_row := to_jsonb(OLD)-ARRAY['t_createdate','t_lastchange','t_user','row_version'];
  after_row := to_jsonb(NEW)-ARRAY['t_createdate','t_lastchange','t_user','row_version'];
  IF before_row=after_row AND NOT (TG_TABLE_NAME='dataset' AND pg_trigger_depth()>1 AND NEW.row_version=OLD.row_version+1) THEN RETURN OLD; END IF;
  IF policy.protection='immutable' THEN RAISE EXCEPTION 'historical decision immutable'; END IF;
  IF policy.protection='grant' THEN
   IF (before_row-ARRAY['revokedat','revocationdecision']) IS DISTINCT FROM (after_row-ARRAY['revokedat','revocationdecision'])
      OR OLD.revokedat IS NOT NULL OR NEW.revokedat IS NULL OR NEW.revocationdecision IS NULL
      OR NEW.revokedat > timezone('UTC',clock_timestamp()) THEN RAISE EXCEPTION 'grant basis immutable; only immediate once-only revoke allowed'; END IF;
  END IF;
  NEW.t_createdate:=OLD.t_createdate; NEW.row_version:=OLD.row_version+1;
 ELSE
  NEW.t_createdate:=timezone('UTC',clock_timestamp()); NEW.row_version:=1;
 END IF;
 NEW.t_lastchange:=timezone('UTC',clock_timestamp()); NEW.t_user:=session_user;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION audit.log_row_change() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE old_json jsonb; new_json jsonb; row_json jsonb; policy audit.table_policy; columns_changed text[];
BEGIN
 PERFORM audit.require_context();
 SELECT * INTO STRICT policy FROM audit.table_policy WHERE table_name=TG_TABLE_NAME;
 IF TG_OP!='INSERT' THEN old_json:=to_jsonb(OLD); END IF;
 IF TG_OP!='DELETE' THEN new_json:=to_jsonb(NEW); END IF;
 IF TG_OP='UPDATE' AND old_json=new_json THEN RETURN NULL; END IF;
 SELECT coalesce(array_agg(key ORDER BY key),ARRAY[]::text[]) INTO columns_changed
 FROM jsonb_object_keys(coalesce(old_json,'{}')||coalesce(new_json,'{}')) key
 WHERE old_json->key IS DISTINCT FROM new_json->key;
 row_json:=coalesce(new_json,old_json);
 INSERT INTO audit.row_change(transaction_id,request_id,command_id,action_code,
 actor_kind,actor_issuer,actor_subject,actor_principal_oid,database_user,schema_name,table_name,
 model_element,schema_revision,object_oid,technical_key,aggregate_dataset_oid,operation,old_values,new_values,changed_columns)
 VALUES(pg_current_xact_id()::text,current_setting('di.request_id')::uuid,current_setting('di.command_id')::uuid,
 current_setting('di.action'),current_setting('di.actor_kind'),current_setting('di.actor_issuer'),current_setting('di.actor_subject'),
 nullif(current_setting('di.actor_principal_oid',true),''),session_user,TG_TABLE_SCHEMA,TG_TABLE_NAME,
 array_to_string(policy.model_elements,','),'management-20261106',row_json->>'t_ili_tid',jsonb_build_object('t_id',row_json->'t_id'),
 CASE WHEN TG_TABLE_NAME='dataset' THEN row_json->>'t_ili_tid' ELSE audit.dataset_oid(TG_TABLE_NAME::name,row_json) END,
 substr(TG_OP,1,1),old_json-policy.redacted_columns,new_json-policy.redacted_columns,columns_changed);
 RETURN NULL;
END $$;
ALTER FUNCTION audit.require_context() OWNER TO di_audit_owner;
ALTER FUNCTION audit.dataset_oid(name,jsonb) OWNER TO di_audit_owner;
ALTER FUNCTION audit.stamp_row() OWNER TO di_audit_owner;
ALTER FUNCTION audit.log_row_change() OWNER TO di_audit_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA audit FROM PUBLIC,di_writer,di_authz,di_public;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA audit FROM PUBLIC;
GRANT USAGE ON SCHEMA di_management TO di_writer,di_authz,di_audit_owner;
GRANT USAGE ON SCHEMA api_public TO di_public;
GRANT SELECT ON ALL TABLES IN SCHEMA di_management TO di_audit_owner;
-- The CSV configuration audit follows its dataset reference. It does not read the source table.
REVOKE SELECT ON di_management.csvdeliveryconfiguration FROM di_audit_owner;
