CREATE TABLE scan_grant(user_id uuid NOT NULL REFERENCES app_user(id),scope_id uuid NOT NULL REFERENCES workflow_scope(id),qualification text NOT NULL CHECK(qualification='SYN-SCAN-1'),valid_until timestamptz NOT NULL,revoked_at timestamptz,PRIMARY KEY(user_id,scope_id));
CREATE TABLE scan_series(slide_id uuid PRIMARY KEY REFERENCES material_entity(id),head bigint NOT NULL DEFAULT -1 CHECK(head>=-1));
CREATE TABLE scan_import(
 id uuid PRIMARY KEY,hospital_id uuid NOT NULL,request_id uuid NOT NULL,case_id uuid NOT NULL,patient_id uuid NOT NULL REFERENCES patient(id),scope_id uuid NOT NULL,
 slide_id uuid NOT NULL REFERENCES material_entity(id),object_id uuid NOT NULL UNIQUE REFERENCES storage_version(id),object_hash text NOT NULL CHECK(object_hash~'^[a-f0-9]{64}$'),object_size bigint NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>=0),previous_id uuid REFERENCES scan_import(id),claimed_patient_id uuid NOT NULL,claimed_case_id uuid NOT NULL,source_basis text NOT NULL CHECK(source_basis~'^[a-f0-9]{64}$'),barcode text NOT NULL,
 source_code text NOT NULL CHECK(source_code~'^SYN-[A-Za-z0-9_-]{1,60}$'),scanner_code text NOT NULL CHECK(scanner_code~'^SYN-[A-Za-z0-9_-]{1,60}$'),reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 500),
 state text NOT NULL CHECK(state IN('QUEUED','RUNNING','RETRY_WAIT','FAILED','CANCELLED','QUARANTINED','PENDING_DIGITAL_QC')),error_code text NOT NULL,version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),lease_id uuid,lease_actor uuid REFERENCES app_user(id),lease_until timestamptz,retry_at timestamptz,width integer,height integer,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),UNIQUE(slide_id,ordinal),FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id),
 CHECK(state<>'RUNNING' OR (lease_id IS NOT NULL AND lease_actor IS NOT NULL AND lease_until IS NOT NULL)),CHECK((width IS NULL AND height IS NULL) OR(width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096))
);
CREATE TABLE scan_event(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),scan_id uuid NOT NULL REFERENCES scan_import(id),version bigint NOT NULL CHECK(version>=0),action text NOT NULL,actor_id uuid NOT NULL REFERENCES app_user(id),reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 500),snapshot jsonb NOT NULL,occurred_at timestamptz NOT NULL DEFAULT statement_timestamp(),UNIQUE(scan_id,version));
CREATE INDEX ix_scan_request ON scan_import(request_id,created_at,id);
CREATE FUNCTION protect_scan_import() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Preserve scan history' USING ERRCODE='23514';END IF;
 IF (to_jsonb(NEW)-ARRAY['state','error_code','version','attempts','lease_id','lease_actor','lease_until','retry_at','width','height']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','error_code','version','attempts','lease_id','lease_actor','lease_until','retry_at','width','height']) OR NEW.version<>OLD.version+1 OR NEW.attempts<>OLD.attempts+(CASE WHEN OLD.state='QUEUED' AND NEW.state='RUNNING' THEN 1 ELSE 0 END) OR NOT(
 (OLD.state='QUEUED' AND NEW.state='RUNNING') OR(OLD.state='RUNNING' AND NEW.state IN('RETRY_WAIT','FAILED','QUARANTINED','PENDING_DIGITAL_QC')) OR(OLD.state='RETRY_WAIT' AND NEW.state='QUEUED') OR(OLD.state<>'CANCELLED' AND NEW.state='CANCELLED')) THEN RAISE EXCEPTION 'Invalid scan transition' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_scan_immutable BEFORE UPDATE OR DELETE ON scan_import FOR EACH ROW EXECUTE FUNCTION protect_scan_import();
CREATE TRIGGER trg_scan_event BEFORE UPDATE OR DELETE ON scan_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION check_scan_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM material_entity m JOIN storage_version o ON o.id=NEW.object_id WHERE m.id=NEW.slide_id AND m.kind='SLIDE' AND m.hospital_id=NEW.hospital_id AND m.request_id=NEW.request_id AND m.case_id=NEW.case_id AND m.patient_id=NEW.patient_id AND o.request_id=m.request_id AND o.case_id=m.case_id AND o.hospital_id=m.hospital_id AND o.sha256=NEW.object_hash AND o.byte_size=NEW.object_size AND o.state='READY') OR (NEW.previous_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM scan_import p WHERE p.id=NEW.previous_id AND p.slide_id=NEW.slide_id AND p.request_id=NEW.request_id AND p.ordinal<NEW.ordinal)) THEN RAISE EXCEPTION 'Scan source mismatch' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_scan_binding BEFORE INSERT ON scan_import FOR EACH ROW EXECUTE FUNCTION check_scan_binding();
CREATE FUNCTION protect_scan_series() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR NEW.slide_id<>OLD.slide_id OR NEW.head<>OLD.head+1 THEN RAISE EXCEPTION 'Preserve scan sequence' USING ERRCODE='23514';END IF;RETURN NEW;END $$;
CREATE TRIGGER trg_scan_series BEFORE UPDATE OR DELETE ON scan_series FOR EACH ROW EXECUTE FUNCTION protect_scan_series();
