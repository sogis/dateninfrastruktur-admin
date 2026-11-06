-- Names below are checked against db/model-mapping.json before installation.
ALTER TABLE di_management.dataset ADD CONSTRAINT dataset_access CHECK(accesslevel IN ('open','restricted'));
ALTER TABLE di_management.dataset ADD CONSTRAINT dataset_kind CHECK(akind IN ('standalone','series'));
ALTER TABLE di_management.dataset ADD CONSTRAINT dataset_publication CHECK(publicationstatus IN ('draft','in_review','published','archived'));
ALTER TABLE di_management.datasetissue ADD CONSTRAINT issue_publication CHECK(publicationstatus IN ('draft','in_review','published','archived'));
ALTER TABLE di_management.datasetissue ADD CONSTRAINT issue_temporal CHECK(NOT(referencedate IS NOT NULL AND (startdate IS NOT NULL OR enddate IS NOT NULL)) AND (startdate IS NULL OR enddate IS NULL OR startdate<=enddate));
ALTER TABLE di_management.authorityassignment ADD CONSTRAINT authority_scope CHECK((office IS NULL)<>(dataset IS NULL));
ALTER TABLE di_management.authorityassignment ADD CONSTRAINT authority_interval CHECK(validuntil IS NULL OR validfrom<validuntil);
ALTER TABLE di_management.groupmembership ADD CONSTRAINT membership_interval CHECK(validuntil IS NULL OR validfrom<validuntil);
ALTER TABLE di_management.groupmembership ADD CONSTRAINT membership_revocation CHECK((revokedat IS NULL)=(revokedby IS NULL));
ALTER TABLE di_management.accessgrant ADD CONSTRAINT grant_interval CHECK(validuntil IS NULL OR validfrom<validuntil);
ALTER TABLE di_management.accessgrant ADD CONSTRAINT grant_revocation CHECK((revokedat IS NULL)=(revocationdecision IS NULL));
ALTER TABLE di_management.accessgrant ADD CONSTRAINT grant_permission CHECK(permission='read_data');
ALTER TABLE di_management.accessrequest ADD CONSTRAINT request_status CHECK(astatus IN ('pending','approved','denied','cancelled'));
ALTER TABLE di_management.accessrequest ADD CONSTRAINT request_interval CHECK(requestedvaliduntil IS NULL OR requestedat<requestedvaliduntil);
CREATE UNIQUE INDEX one_pending_request ON di_management.accessrequest(dataset,applicant) WHERE astatus='pending';
ALTER TABLE di_management.accessdecision ADD CONSTRAINT decision_kind CHECK(akind IN ('approve','deny','direct_grant','revoke'));
ALTER TABLE di_management.accessdecision ADD CONSTRAINT decision_request CHECK((akind IN ('approve','deny'))=(request IS NOT NULL));
ALTER TABLE di_management.principal ADD CONSTRAINT principal_kind CHECK(akind IN ('user','group','service'));
ALTER TABLE di_management.dataversion ADD CONSTRAINT version_availability CHECK(availability IN ('prepared','available','withdrawn'));
ALTER TABLE di_management.dataversion ADD CONSTRAINT version_validation CHECK(validationstatus IN ('unknown','success','fail'));
ALTER TABLE di_management.dataversion ADD CONSTRAINT version_accepted CHECK(availability<>'available' OR (acceptedat IS NOT NULL AND validationstatus<>'fail'));
ALTER TABLE di_management.dataversion ADD CONSTRAINT version_hash CHECK(sourcesha256 IS NULL OR sourcesha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE di_management.dataschema ADD CONSTRAINT schema_hash CHECK(modelsha256 IS NULL OR modelsha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE di_management.distribution ADD CONSTRAINT distribution_hash CHECK(sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE di_management.distribution ADD CONSTRAINT distribution_available CHECK(availability IN ('prepared','available','withdrawn') AND (availability<>'available' OR (bytesize IS NOT NULL AND sha256 IS NOT NULL)));
ALTER TABLE di_management.distribution ADD CONSTRAINT distribution_format CHECK(aformat IN ('csv','xlsx','parquet','other'));
ALTER TABLE di_management.distribution ADD CONSTRAINT distribution_filename CHECK(filename ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$' AND length(filename)<=128);
ALTER TABLE di_management.distribution ADD CONSTRAINT distribution_object_key CHECK(objectkey='downloads/'||t_ili_tid::text||'/'||filename);
CREATE OR REPLACE FUNCTION di_ops.validate_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE row record;
BEGIN
 IF TG_TABLE_NAME='dataset' THEN
  SELECT * INTO row FROM di_management.dataset WHERE t_id=NEW.t_id;
  IF row.currentissue IS NOT NULL AND NOT EXISTS(SELECT 1 FROM di_management.datasetissue i WHERE i.t_id=row.currentissue AND i.dataset=row.t_id) THEN RAISE EXCEPTION 'currentIssue must belong to dataset'; END IF;
  IF row.akind='standalone' AND row.publicationstatus='published' AND
     ((SELECT count(*) FROM di_management.datasetissue i WHERE i.dataset=row.t_id)<>1 OR
      NOT EXISTS(SELECT 1 FROM di_management.datasetissue i WHERE i.dataset=row.t_id AND i.issuekey='default'))
  THEN RAISE EXCEPTION 'published standalone requires exactly one default issue'; END IF;
 ELSIF TG_TABLE_NAME='datasetissue' THEN
  SELECT * INTO row FROM di_management.datasetissue WHERE t_id=NEW.t_id;
  IF row.currentversion IS NOT NULL AND NOT EXISTS(SELECT 1 FROM di_management.dataversion v WHERE v.t_id=row.currentversion AND v.issue=row.t_id AND v.availability='available') THEN RAISE EXCEPTION 'currentVersion must belong to issue and be available'; END IF;
 ELSIF TG_TABLE_NAME='dataversion' THEN
  SELECT * INTO row FROM di_management.dataversion WHERE t_id=NEW.t_id;
  IF EXISTS(SELECT 1 FROM di_management.datasetissue i WHERE i.currentversion=row.t_id
     AND (i.t_id<>row.issue OR row.availability<>'available'))
  THEN RAISE EXCEPTION 'current version must remain available in its issue'; END IF;
 ELSIF TG_TABLE_NAME='accessdecision' THEN
  SELECT * INTO row FROM di_management.accessdecision WHERE t_id=NEW.t_id;
  IF row.request IS NOT NULL AND NOT EXISTS(SELECT 1 FROM di_management.accessrequest r WHERE r.t_id=row.request AND r.dataset=row.dataset AND r.applicant=row.beneficiary) THEN RAISE EXCEPTION 'decision request identity mismatch'; END IF;
 ELSIF TG_TABLE_NAME='accessgrant' THEN
  SELECT * INTO row FROM di_management.accessgrant WHERE t_id=NEW.t_id;
  IF NOT EXISTS(SELECT 1 FROM di_management.accessdecision d WHERE d.t_id=row.grantingdecision AND d.akind IN ('approve','direct_grant') AND d.dataset=row.dataset AND d.beneficiary=row.beneficiary) THEN RAISE EXCEPTION 'grant decision identity mismatch'; END IF;
  IF row.revocationdecision IS NOT NULL AND NOT EXISTS(SELECT 1 FROM di_management.accessdecision d WHERE d.t_id=row.revocationdecision AND d.akind='revoke' AND d.dataset=row.dataset AND d.beneficiary=row.beneficiary) THEN RAISE EXCEPTION 'grant revocation mismatch'; END IF;
 ELSIF TG_TABLE_NAME='groupmembership' THEN
  SELECT * INTO row FROM di_management.groupmembership WHERE t_id=NEW.t_id;
  IF NOT EXISTS(SELECT 1 FROM di_management.principal p WHERE p.t_id=row.agroup AND p.akind='group') OR NOT EXISTS(SELECT 1 FROM di_management.principal p WHERE p.t_id=row.amember AND p.akind IN ('user','service')) THEN RAISE EXCEPTION 'invalid membership principal types'; END IF;
 ELSIF TG_TABLE_NAME='accessrequest' THEN
  SELECT * INTO row FROM di_management.accessrequest WHERE t_id=NEW.t_id;
  IF NOT EXISTS(SELECT 1 FROM di_management.principal p WHERE p.t_id=row.applicant AND p.akind='user') THEN RAISE EXCEPTION 'applicant must be user'; END IF;
 END IF;
 RETURN NULL;
END $$;
ALTER FUNCTION di_ops.validate_references() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ops.validate_references() FROM PUBLIC;
