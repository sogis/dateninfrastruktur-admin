CREATE FUNCTION di_ops.physical_contract() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $contract$
WITH namespaces AS (
    SELECT oid,nspname,nspowner,nspacl
    FROM pg_namespace
    WHERE nspname IN ('di_management','audit','di_ops','api_public','di_ingest')
), relations AS (
    SELECT n.nspname,c.*
    FROM pg_class c JOIN namespaces n ON n.oid=c.relnamespace
    WHERE c.relkind IN ('r','p','v','m','S','f')
), roles AS (
    SELECT *
    FROM pg_roles
    WHERE rolname IN ('di_owner','di_audit_owner','di_writer','di_authz','di_public','di_audit_reader')
), model_metadata(name, row) AS (
    SELECT 'model',to_jsonb(m)-'importdate' FROM di_management.t_ili2db_model m
    UNION ALL
    SELECT 'classname',to_jsonb(m) FROM di_management.t_ili2db_classname m
    UNION ALL
    SELECT 'attrname',to_jsonb(m) FROM di_management.t_ili2db_attrname m
    UNION ALL
    SELECT 'meta_attrs',to_jsonb(m) FROM di_management.t_ili2db_meta_attrs m
    UNION ALL
    SELECT 'trafo',to_jsonb(m) FROM di_management.t_ili2db_trafo m
    UNION ALL
    SELECT 'column_prop',to_jsonb(m) FROM di_management.t_ili2db_column_prop m
    UNION ALL
    SELECT 'table_prop',to_jsonb(m) FROM di_management.t_ili2db_table_prop m
    UNION ALL
    SELECT 'inheritance',to_jsonb(m) FROM di_management.t_ili2db_inheritance m
), objects AS (
    SELECT 'schema' AS kind,nspname::text AS name,
           jsonb_build_array(pg_get_userbyid(nspowner),nspacl) AS attributes
    FROM namespaces
    UNION ALL
    SELECT 'relation',nspname||'.'||relname,
           jsonb_build_array(relkind,pg_get_userbyid(relowner),
               ARRAY(SELECT acl::text FROM unnest(coalesce(relacl,acldefault(
                    (CASE WHEN relkind='S' THEN 's' ELSE 'r' END)::"char",relowner))) acl ORDER BY acl::text COLLATE "C"),
               reloptions,
               relrowsecurity,relforcerowsecurity,relpersistence,relreplident,
               CASE WHEN relkind IN ('v','m') THEN pg_get_viewdef(oid) END)
    FROM relations
    UNION ALL
    SELECT 'column',r.nspname||'.'||r.relname||'.'||a.attname,
           jsonb_build_array(CASE WHEN r.relkind IN ('v','m') THEN a.attnum END,tn.nspname,t.typname,format_type(a.atttypid,a.atttypmod),
               a.attnotnull,a.attidentity,a.attgenerated,a.attacl,pg_get_expr(d.adbin,d.adrelid),
               cn.nspname,coll.collname)
    FROM relations r JOIN pg_attribute a ON a.attrelid=r.oid
    JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace tn ON tn.oid=t.typnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid=r.oid AND d.adnum=a.attnum
    LEFT JOIN pg_collation coll ON coll.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=coll.collnamespace
    WHERE a.attnum>0 AND NOT a.attisdropped
    UNION ALL
    SELECT 'constraint',r.nspname||'.'||r.relname||'.'||c.conname,
           jsonb_build_array(
             CASE WHEN c.contype='c' AND c.conname IN ('decision_kind','decision_request','request_status',
                  'dataset_access','dataset_kind','dataset_publication','issue_publication',
                  'version_availability','version_validation','distribution_available','distribution_format','principal_kind')
               THEN regexp_replace(regexp_replace(pg_get_constraintdef(c.oid),
                  $regex$\(\((ARRAY\[('[a-z_]+'::character varying)(, '[a-z_]+'::character varying)*\])\)::text\[\]\)$regex$,
                  E'(\\1)','g'),
                  $regex$\(('[a-z_]+'::character varying)\)::text$regex$, E'\\1','g')
               ELSE pg_get_constraintdef(c.oid) END,
             c.convalidated,c.condeferrable,c.condeferred,c.connoinherit)
    FROM relations r JOIN pg_constraint c ON c.conrelid=r.oid
    UNION ALL
    SELECT 'index',r.nspname||'.'||r.relname||'.'||i.relname,
           jsonb_build_array(pg_get_indexdef(i.oid),x.indisvalid,x.indisready,x.indislive,x.indisreplident)
    FROM relations r JOIN pg_index x ON x.indrelid=r.oid JOIN pg_class i ON i.oid=x.indexrelid
    UNION ALL
    SELECT 'trigger',r.nspname||'.'||r.relname||'.'||t.tgname,
           jsonb_build_array(pg_get_triggerdef(t.oid),t.tgenabled,t.tgdeferrable,t.tginitdeferred)
    FROM relations r JOIN pg_trigger t ON t.tgrelid=r.oid WHERE NOT t.tgisinternal
    UNION ALL
    SELECT 'routine',n.nspname||'.'||p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',
           jsonb_build_array(pg_get_functiondef(p.oid),pg_get_userbyid(p.proowner),p.proacl,
               p.prosecdef,p.proconfig,p.provolatile)
    FROM pg_proc p JOIN namespaces n ON n.oid=p.pronamespace
    UNION ALL
    SELECT 'sequence',r.nspname||'.'||r.relname,
           jsonb_build_array(s.seqtypid::regtype::text,s.seqstart,s.seqincrement,s.seqmax,s.seqmin,s.seqcache,s.seqcycle,
               parent.nspname,parent.relname,attribute.attname)
    FROM relations r JOIN pg_sequence s ON s.seqrelid=r.oid
    LEFT JOIN pg_depend d ON d.classid='pg_class'::regclass AND d.objid=r.oid
         AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i')
    LEFT JOIN relations parent ON parent.oid=d.refobjid
    LEFT JOIN pg_attribute attribute ON attribute.attrelid=parent.oid AND attribute.attnum=d.refobjsubid
    UNION ALL
    SELECT 'policy',r.nspname||'.'||r.relname||'.'||p.polname,
           jsonb_build_array(p.polcmd,p.polpermissive,
               ARRAY(SELECT CASE WHEN role_id=0 THEN 'PUBLIC' ELSE pg_get_userbyid(role_id) END
                     FROM unnest(p.polroles) role_id ORDER BY 1),
               pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))
    FROM relations r JOIN pg_policy p ON p.polrelid=r.oid
    UNION ALL
    SELECT 'default-acl',coalesce(n.nspname,'*')||'.'||pg_get_userbyid(d.defaclrole)||'.'||d.defaclobjtype::text,
           to_jsonb(d.defaclacl)
    FROM pg_default_acl d LEFT JOIN namespaces n ON n.oid=d.defaclnamespace
    WHERE n.oid IS NOT NULL OR d.defaclnamespace=0 AND d.defaclrole IN (SELECT oid FROM roles)
    UNION ALL
    SELECT 'role',rolname,jsonb_build_array(rolsuper,rolinherit,rolcreaterole,rolcreatedb,
           rolcanlogin,rolreplication,rolbypassrls,rolconfig)
    FROM roles
    UNION ALL
    SELECT 'membership',pg_get_userbyid(m.member)||'.'||pg_get_userbyid(m.roleid)||'.'||pg_get_userbyid(m.grantor),
           jsonb_build_array(m.admin_option,m.inherit_option,m.set_option)
    FROM pg_auth_members m
    WHERE m.member IN (SELECT oid FROM roles) OR m.roleid IN (SELECT oid FROM roles)
    UNION ALL
    SELECT 'ili-metadata',name,jsonb_build_array(count(*),
        encode(sha256(convert_to(jsonb_agg(row ORDER BY row::text COLLATE "C")::text,'UTF8')),'hex'))
    FROM model_metadata GROUP BY name
    UNION ALL
    SELECT 'database','management',jsonb_build_array(pg_get_userbyid(datdba),
        ARRAY(SELECT acl::text FROM unnest(coalesce(datacl,acldefault('d',datdba))) acl ORDER BY acl::text COLLATE "C"))
    FROM pg_database WHERE datname=current_database()
)
SELECT jsonb_agg(jsonb_build_array(kind,name,attributes)
                 ORDER BY kind COLLATE "C",name COLLATE "C",attributes::text COLLATE "C")
FROM objects;
$contract$;

ALTER FUNCTION di_ops.physical_contract() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.physical_contract() FROM PUBLIC;

CREATE FUNCTION di_ops.physical_fingerprint() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
    SELECT encode(sha256(convert_to(di_ops.physical_contract()::text,'UTF8')),'hex');
$$;
ALTER FUNCTION di_ops.physical_fingerprint() OWNER TO di_audit_owner;
REVOKE ALL ON FUNCTION di_ops.physical_fingerprint() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION di_ops.physical_fingerprint() TO di_writer;
