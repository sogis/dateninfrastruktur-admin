-- Technical process state only. INTERLIS remains the source of Fachobjekte.
CREATE SCHEMA di_ingest AUTHORIZATION di_owner;
REVOKE ALL ON SCHEMA di_ingest FROM PUBLIC;
GRANT USAGE ON SCHEMA di_ingest TO di_writer;
CREATE TABLE di_ops.authorization_guard (
 singleton boolean PRIMARY KEY CHECK(singleton), revision bigint NOT NULL DEFAULT 1
);
INSERT INTO di_ops.authorization_guard(singleton) VALUES(true);
ALTER TABLE di_ops.authorization_guard OWNER TO di_owner;
REVOKE ALL ON di_ops.authorization_guard FROM PUBLIC;
GRANT SELECT,UPDATE ON di_ops.authorization_guard TO di_writer;

CREATE TABLE di_ingest.submission (
 id uuid PRIMARY KEY,
 dataset_id bigint NOT NULL REFERENCES di_management.dataset(t_id),
 issue_id bigint NOT NULL REFERENCES di_management.datasetissue(t_id),
 schema_id bigint NOT NULL REFERENCES di_management.dataschema(t_id),
 submitted_by bigint NOT NULL REFERENCES di_management.principal(t_id),
 issuer_snapshot text NOT NULL CHECK(length(issuer_snapshot) BETWEEN 1 AND 1024),
 subject_snapshot text NOT NULL CHECK(length(subject_snapshot) BETWEEN 1 AND 255),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), sealed_at timestamptz,
 idempotency_key uuid NOT NULL, request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 mode text NOT NULL CHECK(mode IN ('replace','append')),
 configuration_digest text NOT NULL CHECK(configuration_digest ~ '^[0-9a-f]{64}$'),
 definition_key text NOT NULL CHECK(length(definition_key) BETWEEN 1 AND 128),
 definition_sha256 text NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
 model_set_sha256 text NOT NULL CHECK(model_set_sha256 ~ '^[0-9a-f]{64}$'),
 configuration_snapshot jsonb NOT NULL CHECK(jsonb_typeof(configuration_snapshot)='object' AND octet_length(configuration_snapshot::text)<=131072),
 original_name text CHECK(length(original_name) BETWEEN 1 AND 255),
 input_size bigint CHECK(input_size>0 AND input_size<=268435456),
 input_sha256 text CHECK(input_sha256 ~ '^[0-9a-f]{64}$'),
 private_spool_reference text CHECK(private_spool_reference='uploads/'||id::text||'/input.csv'),
 status text NOT NULL DEFAULT 'UPLOADING' CHECK(status IN ('UPLOADING','READY','QUEUED','VALIDATING','STAGING','READY_TO_COMMIT','COMMITTED','PUBLISHED','FAILED_RETRYABLE','REJECTED_VALIDATION','REJECTED_AUTHORIZATION','CONFLICT','CANCELLED','UPLOAD_FAILED')),
 status_version bigint NOT NULL DEFAULT 1 CHECK(status_version>0),
 comment text NOT NULL DEFAULT '' CHECK(length(comment)<=4000),
 base_version_id uuid REFERENCES di_management.dataversion(t_ili_tid),
 reserved_version_id uuid NOT NULL UNIQUE,
 published_version_id uuid UNIQUE REFERENCES di_management.dataversion(t_ili_tid),
 publication_revision bigint,
 result_digest text CHECK(result_digest ~ '^[0-9a-f]{64}$'),
 last_error_code text CHECK(last_error_code ~ '^[A-Z_]{1,64}$'),
 safe_message text CHECK(length(safe_message)<=1000),
 UNIQUE(submitted_by,idempotency_key),
 CHECK((sealed_at IS NULL AND input_sha256 IS NULL AND input_size IS NULL AND private_spool_reference IS NULL AND original_name IS NULL)
    OR (sealed_at IS NOT NULL AND input_sha256 IS NOT NULL AND input_size IS NOT NULL AND private_spool_reference IS NOT NULL AND original_name IS NOT NULL)),
 CHECK(status IN ('UPLOADING','UPLOAD_FAILED','CANCELLED') OR sealed_at IS NOT NULL),
 CHECK((status IN ('COMMITTED','PUBLISHED'))=(published_version_id IS NOT NULL)),
 CHECK((published_version_id IS NULL)=(publication_revision IS NULL)),
 CHECK((published_version_id IS NULL)=(result_digest IS NULL))
);
CREATE INDEX submission_dataset_created ON di_ingest.submission(dataset_id,created_at);
CREATE INDEX submission_owner_created ON di_ingest.submission(submitted_by,created_at);
CREATE TABLE di_ingest.dispatch (
 id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES di_ingest.submission(id),
 reserved_by bigint NOT NULL REFERENCES di_management.principal(t_id),
 reservation_command_id uuid NOT NULL, request_digest text NOT NULL CHECK(request_digest ~ '^[0-9a-f]{64}$'),
 attempt_number integer NOT NULL CHECK(attempt_number>0),
 jenkins_instance text NOT NULL CHECK(length(jenkins_instance) BETWEEN 1 AND 128),
 job_name text NOT NULL CHECK(job_name='di-csv-ingest'),
 state text NOT NULL CHECK(state IN ('RESERVED','QUEUED','CLAIMED','CANCELLED','FINISHED')),
 queue_id text CHECK(queue_id ~ '^[0-9]{1,20}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(submission_id,attempt_number), UNIQUE(reserved_by,reservation_command_id), UNIQUE(jenkins_instance,queue_id)
);
CREATE TABLE di_ingest.dataset_execution_slot (
 dataset_id bigint PRIMARY KEY REFERENCES di_management.dataset(t_id),
 submission_id uuid UNIQUE REFERENCES di_ingest.submission(id),
 dispatch_id uuid UNIQUE REFERENCES di_ingest.dispatch(id),
 state text NOT NULL CHECK(state IN ('IDLE','RESERVED','RUNNING','COMMITTED')),
 CHECK((state='IDLE')=(submission_id IS NULL)), CHECK((submission_id IS NULL)=(dispatch_id IS NULL))
);
CREATE TABLE di_ingest.execution (
 id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES di_ingest.submission(id),
 dispatch_id uuid NOT NULL UNIQUE REFERENCES di_ingest.dispatch(id),
 attempt_number integer NOT NULL CHECK(attempt_number>0), fence bigint NOT NULL CHECK(fence>0),
 runner_principal bigint NOT NULL REFERENCES di_management.principal(t_id),
 jenkins_job text NOT NULL CHECK(jenkins_job='di-csv-ingest'),
 jenkins_run_id text NOT NULL CHECK(jenkins_run_id ~ '^[0-9]{1,20}$'),
 state text NOT NULL CHECK(state IN ('ACTIVE','COMMITTED','FINISHED','FAILED','CANCELLED')),
 heartbeat_at timestamptz NOT NULL, lease_expires_at timestamptz NOT NULL,
 lease_secret_hash text NOT NULL CHECK(lease_secret_hash ~ '^[0-9a-f]{64}$'),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
 plan jsonb NOT NULL CHECK(jsonb_typeof(plan)='object' AND octet_length(plan::text)<=131072),
 plan_digest text NOT NULL CHECK(plan_digest ~ '^[0-9a-f]{64}$'),
 UNIQUE(submission_id,attempt_number), UNIQUE(submission_id,fence),
 UNIQUE(jenkins_job,jenkins_run_id), CHECK(heartbeat_at<lease_expires_at)
);
CREATE UNIQUE INDEX one_active_execution ON di_ingest.execution(submission_id) WHERE state='ACTIVE';
CREATE TABLE di_ingest.execution_checkpoint (
 execution_id uuid NOT NULL REFERENCES di_ingest.execution(id),
 checkpoint text NOT NULL CHECK(checkpoint IN ('INPUT_VERIFIED','INCOMING_VALIDATED','SNAPSHOT_VALIDATED','CANDIDATE_VERIFIED','ARTIFACTS_VERIFIED')),
 sequence integer NOT NULL CHECK(sequence BETWEEN 1 AND 5),
 digest text NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
 evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object' AND octet_length(evidence::text)<=32768),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(execution_id,checkpoint), UNIQUE(execution_id,sequence)
);
CREATE TABLE di_ingest.artifact_evidence (
 execution_id uuid NOT NULL REFERENCES di_ingest.execution(id),
 distribution_id uuid NOT NULL, format text NOT NULL CHECK(format IN ('csv','xlsx','parquet')),
 object_key text NOT NULL CHECK(object_key='downloads/'||distribution_id::text||'/data.'||format),
 media_type text NOT NULL CHECK(length(media_type)<=255),
 byte_size bigint NOT NULL CHECK(byte_size>0), sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 snapshot_digest text NOT NULL CHECK(snapshot_digest ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY(execution_id,distribution_id), UNIQUE(execution_id,format)
);
CREATE TABLE di_ingest.event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 submission_id uuid NOT NULL REFERENCES di_ingest.submission(id),
 execution_id uuid REFERENCES di_ingest.execution(id),
 command_id uuid NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 actor_issuer text NOT NULL, actor_subject text NOT NULL,
 initiator_id bigint NOT NULL REFERENCES di_management.principal(t_id),
 event_type text NOT NULL CHECK(event_type ~ '^[A-Z_]{1,64}$'),
 safe_message text NOT NULL CHECK(length(safe_message)<=1000),
 UNIQUE(submission_id,command_id,event_type)
);
CREATE FUNCTION di_ingest.immutable_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ingestion event history is append-only'; END $$;
CREATE TRIGGER immutable_event BEFORE UPDATE OR DELETE ON di_ingest.event
 FOR EACH ROW EXECUTE FUNCTION di_ingest.immutable_event();

CREATE FUNCTION di_ingest.submission_contract() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE allowed boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'submission provenance cannot be deleted'; END IF;
 IF (SELECT dataset FROM di_management.datasetissue WHERE t_id=NEW.issue_id) IS DISTINCT FROM NEW.dataset_id
 THEN RAISE EXCEPTION 'submission issue does not belong to dataset'; END IF;
 IF TG_OP='INSERT' THEN RETURN NEW; END IF;
 IF to_jsonb(OLD)-ARRAY['sealed_at','original_name','input_size','input_sha256','private_spool_reference','status','status_version','base_version_id','published_version_id','publication_revision','result_digest','last_error_code','safe_message']
    IS DISTINCT FROM to_jsonb(NEW)-ARRAY['sealed_at','original_name','input_size','input_sha256','private_spool_reference','status','status_version','base_version_id','published_version_id','publication_revision','result_digest','last_error_code','safe_message']
 THEN RAISE EXCEPTION 'submission identity and configuration immutable'; END IF;
 IF OLD.sealed_at IS NOT NULL AND ROW(OLD.sealed_at,OLD.original_name,OLD.input_size,OLD.input_sha256,OLD.private_spool_reference) IS DISTINCT FROM ROW(NEW.sealed_at,NEW.original_name,NEW.input_size,NEW.input_sha256,NEW.private_spool_reference)
 THEN RAISE EXCEPTION 'sealed input immutable'; END IF;
 IF OLD.published_version_id IS NOT NULL AND ROW(OLD.published_version_id,OLD.publication_revision,OLD.result_digest) IS DISTINCT FROM ROW(NEW.published_version_id,NEW.publication_revision,NEW.result_digest)
 THEN RAISE EXCEPTION 'committed result immutable'; END IF;
 IF OLD.status<>NEW.status THEN
  allowed:=CASE OLD.status
   WHEN 'UPLOADING' THEN NEW.status IN ('READY','CANCELLED','UPLOAD_FAILED')
   WHEN 'READY' THEN NEW.status IN ('QUEUED','CANCELLED','REJECTED_AUTHORIZATION')
   WHEN 'QUEUED' THEN NEW.status IN ('VALIDATING','READY','CANCELLED','REJECTED_AUTHORIZATION')
   WHEN 'VALIDATING' THEN NEW.status IN ('STAGING','REJECTED_VALIDATION','FAILED_RETRYABLE','REJECTED_AUTHORIZATION','CANCELLED')
   WHEN 'STAGING' THEN NEW.status IN ('READY_TO_COMMIT','FAILED_RETRYABLE','REJECTED_VALIDATION','REJECTED_AUTHORIZATION','CANCELLED')
   WHEN 'READY_TO_COMMIT' THEN NEW.status IN ('COMMITTED','FAILED_RETRYABLE','REJECTED_AUTHORIZATION','CONFLICT','CANCELLED')
   WHEN 'COMMITTED' THEN NEW.status='PUBLISHED'
   WHEN 'FAILED_RETRYABLE' THEN NEW.status IN ('READY','CANCELLED','REJECTED_AUTHORIZATION')
   ELSE false END;
  IF NOT allowed OR NEW.status_version<>OLD.status_version+1 THEN RAISE EXCEPTION 'invalid submission transition or revision'; END IF;
 ELSIF NEW.status_version<>OLD.status_version THEN RAISE EXCEPTION 'status revision without transition'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER submission_contract BEFORE INSERT OR UPDATE OR DELETE ON di_ingest.submission
 FOR EACH ROW EXECUTE FUNCTION di_ingest.submission_contract();
ALTER FUNCTION di_ingest.submission_contract() OWNER TO di_owner;
REVOKE ALL ON FUNCTION di_ingest.submission_contract(),di_ingest.immutable_event() FROM PUBLIC;
ALTER TABLE di_ingest.submission OWNER TO di_owner;
ALTER TABLE di_ingest.dispatch OWNER TO di_owner;
ALTER TABLE di_ingest.dataset_execution_slot OWNER TO di_owner;
ALTER TABLE di_ingest.execution OWNER TO di_owner;
ALTER TABLE di_ingest.execution_checkpoint OWNER TO di_owner;
ALTER TABLE di_ingest.artifact_evidence OWNER TO di_owner;
ALTER TABLE di_ingest.event OWNER TO di_owner;
GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA di_ingest TO di_writer;
REVOKE UPDATE ON di_ingest.event FROM di_writer;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA di_ingest TO di_writer;
