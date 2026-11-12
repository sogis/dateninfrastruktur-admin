-- ili2pg 5.5.2 generates varchar(200) for textual object TIDs, independently
-- of DCOID's declared TEXT*1024. Preserve the full domain at import/export.
ALTER TABLE di_management.office ALTER COLUMN t_ili_tid TYPE varchar(1024);
ALTER TABLE di_management.contactpoint ALTER COLUMN t_ili_tid TYPE varchar(1024);

-- ili2pg does not mark object TIDs NOT NULL or UNIQUE. Structure and
-- association rows have no object TID and are deliberately excluded.
ALTER TABLE di_management.office ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.contactpoint ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.dataset ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.datasetissue ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.dataschema ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.attributedefinition ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.uniquedefinition ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.uniquefield ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.deliveryconfiguration ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.dataversion ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.distribution ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.principal ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.groupmembership ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.authorityassignment ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.accessrequest ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.accessdecision ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.accessgrant ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.catalogaction ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);
ALTER TABLE di_management.submission ALTER COLUMN t_ili_tid SET NOT NULL, ADD UNIQUE (t_ili_tid);

CREATE SCHEMA di_api_public;
CREATE VIEW di_api_public.datasets WITH (security_barrier=true) AS
SELECT d.t_ili_tid AS id, d.identifier, d.akind AS kind, d.title, d.adescription AS description,
       d.accesslevel AS access_level, d.origin, d.frequency, d.issued, d.modified,
       d.licenseuri AS license_uri, d.surveymethod AS survey_method, d.furtheruses AS further_uses,
       d.auxiliarydata AS auxiliary_data, d.dataavailablefrom AS data_available_from,
       o.t_ili_tid AS owner_office, p.t_ili_tid AS publisher_office, i.t_ili_tid AS current_issue
FROM di_management.dataset d
JOIN di_management.office o ON o.t_id=d.owneroffice
JOIN di_management.office p ON p.t_id=d.publisheroffice
LEFT JOIN di_management.datasetissue i
    ON i.t_id=d.currentissue AND i.dataset=d.t_id AND i.publicationstatus='published'
WHERE d.publicationstatus='published';

CREATE VIEW di_api_public.issues WITH (security_barrier=true) AS
SELECT i.t_ili_tid AS id, d.id AS dataset_id, i.issuekey AS issue_key, i.label,
       i.referencedate AS reference_date, i.startdate AS start_date, i.enddate AS end_date,
       i.note, i.issued, i.modified, v.t_ili_tid AS current_version
FROM di_management.datasetissue i
JOIN di_management.dataset root ON root.t_id=i.dataset
JOIN di_api_public.datasets d ON d.id=root.t_ili_tid
LEFT JOIN di_management.dataversion v
    ON v.t_id=i.currentversion AND v.issue=i.t_id AND v.availability='available'
WHERE i.publicationstatus='published';

CREATE VIEW di_api_public.versions WITH (security_barrier=true) AS
SELECT v.t_ili_tid AS id, i.id AS issue_id, v.versionkey AS version_key, v.asequence AS sequence,
       s.t_ili_tid AS schema_id, v.acceptedat AS accepted_at, v.sourcesha256 AS source_sha256,
       v.objectcount AS object_count, v.attributecount AS attribute_count,
       v.validationstatus AS validation_status, v.validationat AS validation_at,
       v.validationerrors AS validation_errors
FROM di_management.dataversion v
JOIN di_management.datasetissue issue ON issue.t_id=v.issue
JOIN di_api_public.issues i ON i.id=issue.t_ili_tid
JOIN di_management.dataschema s ON s.t_id=v.aschema
WHERE v.availability='available';

CREATE VIEW di_api_public.distributions WITH (security_barrier=true) AS
SELECT x.t_ili_tid AS id, v.id AS version_id, x.aformat AS format, x.filename AS file_name,
       x.mediatype AS media_type, x.bytesize AS byte_size, x.sha256,
       '/downloads/' || x.t_ili_tid || '/' || x.filename AS download_path
FROM di_management.distribution x
JOIN di_management.dataversion version ON version.t_id=x.version
JOIN di_api_public.versions v ON v.id=version.t_ili_tid
WHERE x.availability='available' AND x.bytesize IS NOT NULL AND x.sha256 ~ '^[0-9a-f]{64}$'
  AND x.filename ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$';

CREATE VIEW di_api_public.offices WITH (security_barrier=true) AS
SELECT o.t_ili_tid AS id, o.identifier, o.aname AS name, o.abbreviation, o.email, o.phone, o.website
FROM di_management.office o
WHERE o.t_ili_tid IN (SELECT owner_office FROM di_api_public.datasets
                      UNION SELECT publisher_office FROM di_api_public.datasets);

CREATE VIEW di_api_public.themes WITH (security_barrier=true) AS
SELECT d.id AS dataset_id, t.acode AS theme
FROM di_management.themeentry t
JOIN di_management.dataset root ON root.t_id=t.dataset_themes
JOIN di_api_public.datasets d ON d.id=root.t_ili_tid;

CREATE VIEW di_api_public.keywords WITH (security_barrier=true) AS
SELECT d.id AS dataset_id, k.avalue AS keyword
FROM di_management.keywordentry k
JOIN di_management.dataset root ON root.t_id=k.dataset_keywords
JOIN di_api_public.datasets d ON d.id=root.t_ili_tid;

CREATE VIEW di_api_public.contacts WITH (security_barrier=true) AS
SELECT d.id AS dataset_id, c.t_ili_tid AS id, c.aname AS name, c.unit, c.email, c.phone, c.url
FROM di_management.datasetcontact link
JOIN di_management.contactpoint c ON c.t_id=link.contactpoints
JOIN di_management.dataset root ON root.t_id=link.datasets
JOIN di_api_public.datasets d ON d.id=root.t_ili_tid;

CREATE VIEW di_api_public.schemas WITH (security_barrier=true) AS
SELECT s.t_ili_tid AS id, s.identifier, s.akind AS kind, s.modelname AS model_name,
       s.modeluri AS model_uri, s.modelversion AS model_version, s.adescription AS description
FROM di_management.dataschema s
WHERE s.t_ili_tid IN (SELECT schema_id FROM di_api_public.versions);

CREATE VIEW di_api_public.attributes WITH (security_barrier=true) AS
SELECT a.t_ili_tid AS id, s.id AS schema_id, a.aname AS name, a.datatype AS data_type,
       a.ordinal, a.mandatory, a.adescription AS description, a.unit, a.codelist AS code_list
FROM di_management.attributedefinition a
JOIN di_management.dataschema schema ON schema.t_id=a.aschema
JOIN di_api_public.schemas s ON s.id=schema.t_ili_tid;

REVOKE ALL ON SCHEMA di_management, di_api_public FROM PUBLIC;
GRANT USAGE ON SCHEMA di_api_public TO di_public;
GRANT SELECT ON ALL TABLES IN SCHEMA di_api_public TO di_public;
GRANT USAGE ON SCHEMA di_management TO di_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA di_management TO di_writer;
GRANT INSERT, UPDATE, DELETE ON
    di_management.office, di_management.contactpoint, di_management.dataset,
    di_management.datasetissue, di_management.dataschema, di_management.attributedefinition,
    di_management.attributedefinition_enumvalues, di_management.uniquedefinition,
    di_management.uniquefield, di_management.deliveryconfiguration, di_management.dataversion,
    di_management.distribution, di_management.principal, di_management.groupmembership,
    di_management.authorityassignment, di_management.accessrequest, di_management.accessdecision,
    di_management.accessgrant, di_management.catalogaction, di_management.submission,
    di_management.datasetcontact, di_management.themeentry, di_management.keywordentry TO di_writer;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA di_management TO di_writer;
