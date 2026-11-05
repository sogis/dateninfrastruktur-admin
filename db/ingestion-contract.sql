-- Read-only fail-closed runtime verification; never performs an implicit migration.
GRANT SELECT ON di_ops.schema_revision TO di_owner;
CREATE OR REPLACE FUNCTION di_ops.ingestion_contract() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
 SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision='MA-G2-1')
 AND EXISTS(SELECT 1 FROM di_ops.authorization_guard WHERE singleton AND revision>0)
 AND (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='di_ingest' AND c.relkind='r')=7
 AND NOT EXISTS(
  SELECT 1 FROM (VALUES ('authorityassignment',30),('groupmembership',30),('principal',30),('dataset',18),('datasetissue',18),('office',18),('csvdeliveryconfiguration',30),('dataschema',30),('attributedefinition',30)) expected(table_name,trigger_type)
  WHERE NOT EXISTS(SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='di_management' AND c.relname=expected.table_name AND t.tgname='authorization_guard' AND t.tgenabled='O' AND t.tgtype=expected.trigger_type
      AND t.tgfoid='di_ops.advance_authorization_revision()'::regprocedure))
 AND NOT EXISTS(
  SELECT 1 FROM (VALUES ('submission'),('dispatch'),('dataset_execution_slot'),('execution'),('execution_checkpoint'),('artifact_evidence')) expected(table_name)
  WHERE NOT EXISTS(SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='di_ingest' AND c.relname=expected.table_name AND t.tgname='command_context' AND t.tgenabled='O'
      AND t.tgfoid='di_ingest.require_command_context()'::regprocedure))
 AND EXISTS(SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='di_management' AND c.relname='csvdeliveryconfiguration' AND i.indisunique AND i.indisvalid AND i.indnkeyatts=1 AND pg_get_indexdef(i.indexrelid,1,true)='dataset')
 AND NOT EXISTS(
  SELECT 1 FROM (VALUES
   ('datasetissue','di_issue_dataset_key','dataset','issuekey'),
   ('dataversion','di_version_issue_key','issue','versionkey'),
   ('dataversion','di_version_issue_sequence','issue','asequence'),
   ('attributedefinition','di_attribute_schema_name','aschema','aname'),
   ('attributedefinition','di_attribute_schema_ordinal','aschema','ordinal'),
   ('datasettheme','di_theme_dataset_value','dataset','theme'),
   ('datasetkeyword','di_keyword_dataset_value','dataset','keyword')) expected(table_name,index_name,key_one,key_two)
  WHERE NOT EXISTS(SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_class idx ON idx.oid=i.indexrelid
   WHERE n.nspname='di_management' AND c.relname=expected.table_name AND idx.relname=expected.index_name AND i.indisunique AND i.indisvalid AND i.indpred IS NULL AND i.indnkeyatts=2
    AND pg_get_indexdef(i.indexrelid,1,true)=expected.key_one AND pg_get_indexdef(i.indexrelid,2,true)=expected.key_two))
 AND NOT has_schema_privilege('di_public','di_ingest','USAGE')
 AND NOT has_schema_privilege('di_authz','di_ingest','USAGE')
 AND NOT has_table_privilege('di_public','di_ingest.submission','SELECT')
 AND NOT has_table_privilege('di_authz','di_ingest.submission','SELECT')
 AND NOT has_table_privilege('di_writer','di_ingest.event','UPDATE')
 AND NOT has_table_privilege('di_writer','di_ingest.event','DELETE');
$$;
ALTER FUNCTION di_ops.ingestion_contract() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.ingestion_contract() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION di_ops.ingestion_contract() TO di_writer,di_authz;
