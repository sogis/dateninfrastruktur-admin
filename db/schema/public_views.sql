CREATE OR REPLACE VIEW api_public.datasets WITH (security_barrier=true) AS
SELECT d.t_ili_tid AS id,d.identifier,d.akind AS kind,d.title,d.adescription AS description,
 d.contactname AS contact_name,d.contactunit AS contact_unit,d.contactemail AS contact_email,
 d.contactphone AS contact_phone,d.contacturl AS contact_url,d.accesslevel AS access_level,
 d.origin,d.frequency,d.issued,d.modified,d.licenseuri AS license_uri,d.surveymethod AS survey_method,
 d.furtheruses AS further_uses,d.auxiliarydata AS auxiliary_data,d.dataavailablefrom AS data_available_from,
 o.t_ili_tid AS owner_office,p.t_ili_tid AS publisher_office,i.t_ili_tid AS current_issue
FROM di_management.dataset d JOIN di_management.office o ON o.t_id=d.owneroffice
JOIN di_management.office p ON p.t_id=d.publisheroffice
LEFT JOIN di_management.datasetissue i ON i.t_id=d.currentissue AND i.dataset=d.t_id AND i.publicationstatus='published'
WHERE d.publicationstatus='published';
CREATE OR REPLACE VIEW api_public.issues WITH (security_barrier=true) AS
SELECT i.t_ili_tid AS id,d.t_ili_tid AS dataset_id,i.issuekey AS issue_key,i.label,
 i.referencedate AS reference_date,i.startdate AS start_date,i.enddate AS end_date,i.note,
 i.issued,i.modified,v.t_ili_tid AS current_version
FROM di_management.datasetissue i JOIN di_management.dataset d ON d.t_id=i.dataset
LEFT JOIN di_management.dataversion v ON v.t_id=i.currentversion AND v.issue=i.t_id AND v.availability='available'
WHERE d.publicationstatus='published' AND i.publicationstatus='published';
CREATE OR REPLACE VIEW api_public.versions WITH (security_barrier=true) AS
SELECT v.t_ili_tid AS id,i.t_ili_tid AS issue_id,v.versionkey AS version_key,v.asequence AS sequence,
 s.t_ili_tid AS schema_id,v.acceptedat AS accepted_at,v.sourcesha256 AS source_sha256,
 v.objectcount AS object_count,v.attributecount AS attribute_count,
 v.validationstatus AS validation_status,v.validationat AS validation_at,v.validationerrors AS validation_errors
FROM di_management.dataversion v JOIN di_management.datasetissue i ON i.t_id=v.issue
JOIN di_management.dataset d ON d.t_id=i.dataset LEFT JOIN di_management.dataschema s ON s.t_id=v.aschema
WHERE d.publicationstatus='published' AND i.publicationstatus='published' AND v.availability='available';
CREATE OR REPLACE VIEW api_public.distributions WITH (security_barrier=true) AS
SELECT x.t_ili_tid AS id,v.t_ili_tid AS version_id,x.aformat AS format,x.filename AS file_name,
 x.mediatype AS media_type,x.bytesize AS byte_size,x.sha256,
 '/downloads/'||x.t_ili_tid::text||'/'||x.filename AS download_path
FROM di_management.distribution x JOIN di_management.dataversion v ON v.t_id=x.version
JOIN api_public.versions visible ON visible.id=v.t_ili_tid WHERE x.availability='available';
CREATE OR REPLACE VIEW api_public.offices WITH (security_barrier=true) AS
SELECT o.t_ili_tid AS id,o.identifier,o.aname AS name,o.abbreviation,o.email,o.phone,o.website
FROM di_management.office o WHERE o.t_ili_tid IN
 (SELECT owner_office FROM api_public.datasets UNION SELECT publisher_office FROM api_public.datasets);
CREATE OR REPLACE VIEW api_public.themes WITH (security_barrier=true) AS
SELECT d.id AS dataset_id,t.theme FROM di_management.datasettheme t
JOIN di_management.dataset root ON root.t_id=t.dataset JOIN api_public.datasets d ON d.id=root.t_ili_tid;
CREATE OR REPLACE VIEW api_public.keywords WITH (security_barrier=true) AS
SELECT d.id AS dataset_id,k.keyword FROM di_management.datasetkeyword k
JOIN di_management.dataset root ON root.t_id=k.dataset JOIN api_public.datasets d ON d.id=root.t_ili_tid;
CREATE OR REPLACE VIEW api_public.schemas WITH (security_barrier=true) AS
SELECT s.t_ili_tid AS id,s.identifier,s.modelname AS model_name,s.modeluri AS model_uri,
 s.modelversion AS model_version,s.modelsha256 AS model_sha256,s.adescription AS description
FROM di_management.dataschema s WHERE s.t_ili_tid IN (SELECT schema_id FROM api_public.versions);
CREATE OR REPLACE VIEW api_public.attributes WITH (security_barrier=true) AS
SELECT a.t_ili_tid AS id,s.t_ili_tid AS schema_id,a.aname AS name,a.datatype AS data_type,
 a.ordinal,a.mandatory,a.adescription AS description,a.unit,a.codelist AS code_list
FROM di_management.attributedefinition a JOIN di_management.dataschema s ON s.t_id=a.aschema
JOIN api_public.schemas visible ON visible.id=s.t_ili_tid;
ALTER VIEW api_public.datasets OWNER TO di_owner;
ALTER VIEW api_public.issues OWNER TO di_owner;
ALTER VIEW api_public.versions OWNER TO di_owner;
ALTER VIEW api_public.distributions OWNER TO di_owner;
ALTER VIEW api_public.offices OWNER TO di_owner;
ALTER VIEW api_public.themes OWNER TO di_owner;
ALTER VIEW api_public.keywords OWNER TO di_owner;
ALTER VIEW api_public.schemas OWNER TO di_owner;
ALTER VIEW api_public.attributes OWNER TO di_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA api_public FROM PUBLIC;
GRANT SELECT ON api_public.datasets,api_public.issues,api_public.versions,api_public.distributions,
 api_public.offices,api_public.themes,api_public.keywords,api_public.schemas,api_public.attributes TO di_public;
