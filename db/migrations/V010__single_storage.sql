-- Explicit known MA-F -> MA-G1 upgrade only. Never run on normal startup.
DO $$ BEGIN
 IF EXISTS (SELECT objectkey FROM di_management.distribution GROUP BY objectkey HAVING count(*)>1)
 THEN RAISE EXCEPTION 'Distribution object keys collide; no schema change authorized'; END IF;
END $$;
DROP INDEX di_management.distribution_objectkey_storageprofile_key;
ALTER TABLE di_management.distribution DROP COLUMN storageprofile, DROP COLUMN legacyaccessurl;
CREATE UNIQUE INDEX distribution_objectkey_key ON di_management.distribution(objectkey);
-- Preserve basket IDs/dataset bindings; only the known structural topic name evolves.
UPDATE di_management.t_ili2db_basket SET topic=replace(topic,'20261104','20261105')
 WHERE topic IN ('SO_AGI_DataInfrastructure_Catalog_20261104.Catalog','SO_AGI_DataInfrastructure_Access_20261104.Access');
UPDATE audit.table_policy SET model_elements=ARRAY(
 SELECT replace(element,'20261104','20261105') FROM unnest(model_elements) element);
INSERT INTO di_ops.schema_revision(revision) VALUES('MA-G1-1');
