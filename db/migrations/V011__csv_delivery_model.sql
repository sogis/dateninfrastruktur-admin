-- Physical additive delta derived from stock ili2pg5.5.2 G2 reference, not a schema reimport.
-- Source fingerprint/mapping verified before this transaction; no existing UUID or data rewrite.
ALTER TABLE di_management.authorityassignment ADD COLUMN "revocationreason" text;
ALTER TABLE di_management.authorityassignment ADD COLUMN "revokedby" bigint;
ALTER TABLE di_management.catalogaction ADD COLUMN "initiator" bigint;
CREATE TABLE di_management.csvdeliveryconfiguration (
 "t_id" bigint DEFAULT nextval('di_management.t_ili2db_seq'::regclass) NOT NULL,
 "t_ili_tid" uuid DEFAULT uuid_generate_v4(),
 "enabled" boolean NOT NULL,
 "automaticpublication" boolean NOT NULL,
 "definitionkey" character varying(128) NOT NULL,
 "definitionsha256" character varying(64) NOT NULL,
 "allowedmodes" character varying(255) NOT NULL,
 "dataset" bigint NOT NULL,
 "aschema" bigint NOT NULL,
 "t_lastchange" timestamp without time zone NOT NULL,
 "t_createdate" timestamp without time zone NOT NULL,
 "t_user" character varying(40) NOT NULL
);
ALTER TABLE di_management.authorityassignment ADD CONSTRAINT authorityassignment_revokedby_fkey FOREIGN KEY (revokedby) REFERENCES di_management.principal(t_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE di_management.catalogaction ADD CONSTRAINT catalogaction_initiator_fkey FOREIGN KEY (initiator) REFERENCES di_management.principal(t_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csvdeliveryconfiguration_aschema_fkey FOREIGN KEY (aschema) REFERENCES di_management.dataschema(t_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csvdeliveryconfiguration_dataset_fkey FOREIGN KEY (dataset) REFERENCES di_management.dataset(t_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE di_management.csvdeliveryconfiguration ADD CONSTRAINT csvdeliveryconfiguration_pkey PRIMARY KEY (t_id);
CREATE INDEX authorityassignment_revokedby_idx ON di_management.authorityassignment USING btree (revokedby);
CREATE INDEX catalogaction_initiator_idx ON di_management.catalogaction USING btree (initiator);
CREATE INDEX csvdeliveryconfiguration_aschema_idx ON di_management.csvdeliveryconfiguration USING btree (aschema);
CREATE INDEX csvdeliveryconfiguration_dataset_idx ON di_management.csvdeliveryconfiguration USING btree (dataset);
