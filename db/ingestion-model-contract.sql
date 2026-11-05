-- Shared hardening of the real fresh and explicitly upgraded ili2pg G2 schema.
ALTER TABLE di_management.authorityassignment DROP CONSTRAINT authority_capability;
ALTER TABLE di_management.authorityassignment ADD CONSTRAINT authority_capability
 CHECK(capability IN ('manage_metadata','publish','manage_access','submit_data','manage_submitters'));
ALTER TABLE di_management.authorityassignment ADD CONSTRAINT authority_revocation
 CHECK((revokedat IS NULL AND revokedby IS NULL AND revocationreason IS NULL) OR
       (revokedat IS NOT NULL AND revokedby IS NOT NULL AND revocationreason IS NOT NULL AND length(btrim(revocationreason))>0));
-- ili2pg does not translate the role-based UNIQUE/cardinality in this profile.
CREATE UNIQUE INDEX csv_delivery_one_dataset ON di_management.csvdeliveryconfiguration(dataset);
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csv_definition_hash CHECK(definitionsha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csv_definition_key CHECK(definitionkey ~ '^[a-z][a-z0-9_-]{0,127}$');
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csv_allowed_modes CHECK(allowedmodes IN ('replace_only','append_only','replace_and_append'));
ALTER TABLE di_management.catalogaction ADD CONSTRAINT delivery_initiator
 CHECK(actioncode<>'accept_delivery' OR (initiator IS NOT NULL AND affectedversion IS NOT NULL));

CREATE FUNCTION di_ops.advance_authorization_revision() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 UPDATE di_ops.authorization_guard SET revision=revision+1 WHERE singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'authorization guard missing'; END IF;
 RETURN NULL;
END $$;
ALTER FUNCTION di_ops.advance_authorization_revision() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.advance_authorization_revision() FROM PUBLIC;
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OR DELETE ON di_management.authorityassignment
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OR DELETE ON di_management.groupmembership
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OF aactive OR DELETE ON di_management.principal
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE UPDATE OF owneroffice,publicationstatus ON di_management.dataset
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE UPDATE OF aactive ON di_management.office
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();

CREATE TRIGGER authorization_guard BEFORE UPDATE OF publicationstatus ON di_management.datasetissue
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();

-- Configuration/schema mutations also participate in the same commit ordering.
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OR DELETE ON di_management.csvdeliveryconfiguration
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OR DELETE ON di_management.dataschema
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();
CREATE TRIGGER authorization_guard BEFORE INSERT OR UPDATE OR DELETE ON di_management.attributedefinition
 FOR EACH STATEMENT EXECUTE FUNCTION di_ops.advance_authorization_revision();

CREATE FUNCTION di_ops.structural_schema_used(schema_key bigint) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_management.dataversion v WHERE v.aschema=schema_key AND v.acceptedat IS NOT NULL)
 OR EXISTS(SELECT 1 FROM di_ingest.submission s WHERE s.schema_id=schema_key AND s.sealed_at IS NOT NULL)
$$;
ALTER FUNCTION di_ops.structural_schema_used(bigint) OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.structural_schema_used(bigint) FROM PUBLIC;
CREATE OR REPLACE FUNCTION di_ops.protect_delivery() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous jsonb; upcoming jsonb; schema_key bigint;
BEGIN
 previous:=to_jsonb(OLD)-ARRAY['t_lastchange','t_createdate','t_user','row_version'];
 IF TG_OP='UPDATE' THEN upcoming:=to_jsonb(NEW)-ARRAY['t_lastchange','t_createdate','t_user','row_version']; IF previous=upcoming THEN RETURN NEW; END IF; END IF;
 IF TG_TABLE_NAME IN ('dataversion','distribution') AND previous->>'availability' IN ('available','withdrawn') THEN
  IF TG_OP='DELETE' OR previous->>'availability'='withdrawn' OR upcoming->>'availability'<>'withdrawn' OR previous-'availability' IS DISTINCT FROM upcoming-'availability' THEN RAISE EXCEPTION 'delivery identity and content immutable after activation'; END IF;
 ELSIF TG_TABLE_NAME='dataset' AND TG_OP='UPDATE' AND previous->>'publicationstatus'='published' AND previous->>'akind'<>upcoming->>'akind' THEN RAISE EXCEPTION 'published dataset kind immutable';
 ELSIF TG_TABLE_NAME='datasetissue' AND TG_OP='UPDATE' AND previous->>'dataset'<>upcoming->>'dataset' THEN RAISE EXCEPTION 'issue parent immutable';
 ELSIF TG_TABLE_NAME='principal' AND TG_OP='UPDATE' THEN
  IF previous-ARRAY['aactive','displayname'] IS DISTINCT FROM upcoming-ARRAY['aactive','displayname'] THEN RAISE EXCEPTION 'principal external identity immutable'; END IF;
 ELSIF TG_TABLE_NAME='accessrequest' AND TG_OP='UPDATE' THEN
  IF previous-ARRAY['astatus','reason'] IS DISTINCT FROM upcoming-ARRAY['astatus','reason']
     OR (previous->>'astatus'<>'pending' AND previous->>'astatus'<>upcoming->>'astatus')
  THEN RAISE EXCEPTION 'request basis and terminal status immutable'; END IF;
 ELSIF TG_TABLE_NAME IN ('dataschema','attributedefinition') THEN
  schema_key:=CASE WHEN TG_TABLE_NAME='dataschema' THEN (previous->>'t_id')::bigint ELSE (previous->>'aschema')::bigint END;
  IF TG_OP='UPDATE' AND previous-'adescription' IS NOT DISTINCT FROM upcoming-'adescription' THEN RETURN NEW; END IF;
  IF di_ops.structural_schema_used(schema_key) THEN RAISE EXCEPTION 'bound structural schema immutable'; END IF;
  IF TG_TABLE_NAME='attributedefinition' AND TG_OP='UPDATE' AND di_ops.structural_schema_used((upcoming->>'aschema')::bigint)
  THEN RAISE EXCEPTION 'bound target schema immutable'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION di_ops.freeze_attribute_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF di_ops.structural_schema_used(NEW.aschema) THEN RAISE EXCEPTION 'bound structural schema immutable'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION di_ops.validate_csv_configuration() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE schema_row record;
BEGIN
 SELECT * INTO schema_row FROM di_management.dataschema WHERE t_id=NEW.aschema;
 IF NOT FOUND OR schema_row.modelname IS NULL OR schema_row.modelversion IS NULL OR schema_row.modelsha256 IS NULL
 THEN RAISE EXCEPTION 'CSV configuration requires resolved model identity and hash'; END IF;
 RETURN NEW;
END $$;
ALTER FUNCTION di_ops.validate_csv_configuration() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.validate_csv_configuration() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER validate_csv_configuration AFTER INSERT OR UPDATE ON di_management.csvdeliveryconfiguration
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION di_ops.validate_csv_configuration();

CREATE FUNCTION di_ingest.require_command_context() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 IF coalesce(current_setting('di.actor_issuer',true),'')='' OR coalesce(current_setting('di.actor_subject',true),'')=''
 OR coalesce(current_setting('di.command_id',true),'')='' OR coalesce(current_setting('di.request_id',true),'')=''
 OR coalesce(current_setting('di.actor_kind',true),'') NOT IN ('user','service')
 THEN RAISE EXCEPTION 'ingestion command requires verified actor context'; END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION di_ingest.require_command_context() FROM PUBLIC;
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.submission
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.dispatch
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.dataset_execution_slot
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.execution
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.execution_checkpoint
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE TRIGGER command_context BEFORE INSERT OR UPDATE ON di_ingest.artifact_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION di_ingest.require_command_context();
CREATE FUNCTION di_ingest.event_context() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF coalesce(current_setting('di.actor_issuer',true),'')='' OR coalesce(current_setting('di.actor_subject',true),'')=''
 THEN RAISE EXCEPTION 'event requires verified actor'; END IF;
 NEW.actor_issuer:=current_setting('di.actor_issuer');NEW.actor_subject:=current_setting('di.actor_subject');
 NEW.command_id:=current_setting('di.command_id')::uuid;
 SELECT submitted_by INTO NEW.initiator_id FROM di_ingest.submission WHERE id=NEW.submission_id;
 IF NEW.execution_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM di_ingest.execution WHERE id=NEW.execution_id AND submission_id=NEW.submission_id)
 THEN RAISE EXCEPTION 'event execution binding mismatch'; END IF;
 RETURN NEW;
END $$;
ALTER FUNCTION di_ingest.event_context() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ingest.event_context() FROM PUBLIC;
CREATE TRIGGER event_context BEFORE INSERT ON di_ingest.event FOR EACH ROW EXECUTE FUNCTION di_ingest.event_context();

-- Replace the known MA-B 16-table runtime coverage with the G2 17-table contract.
CREATE OR REPLACE FUNCTION di_ops.runtime_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision='MA-B-2')
 AND (SELECT count(*) FROM audit.table_policy)=17
 AND (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='di_management' AND c.relkind='r' AND c.relname NOT LIKE 't_ili2db_%')=17
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

-- ili2pg 5.6.x emits association cardinalities, but not all INTERLIS UNIQUEs.
-- Enforce the existing logical keys with the actual imported column names.
CREATE UNIQUE INDEX IF NOT EXISTS di_issue_dataset_key ON di_management.datasetissue(dataset,issuekey);
CREATE UNIQUE INDEX IF NOT EXISTS di_version_issue_key ON di_management.dataversion(issue,versionkey);
CREATE UNIQUE INDEX IF NOT EXISTS di_version_issue_sequence ON di_management.dataversion(issue,asequence);
CREATE UNIQUE INDEX IF NOT EXISTS di_attribute_schema_name ON di_management.attributedefinition(aschema,aname);
CREATE UNIQUE INDEX IF NOT EXISTS di_attribute_schema_ordinal ON di_management.attributedefinition(aschema,ordinal);
CREATE UNIQUE INDEX IF NOT EXISTS di_theme_dataset_value ON di_management.datasettheme(dataset,theme);
CREATE UNIQUE INDEX IF NOT EXISTS di_keyword_dataset_value ON di_management.datasetkeyword(dataset,keyword);
