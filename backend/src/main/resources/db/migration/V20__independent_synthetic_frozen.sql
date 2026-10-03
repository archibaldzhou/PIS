-- No grants, patient data, clinical rules or timers are seeded.
ALTER TABLE report_revision ADD CONSTRAINT uq_report_frozen_link UNIQUE(case_id,id,template_code,template_version);
ALTER TABLE report_review_event ADD CONSTRAINT uq_review_frozen_link UNIQUE(case_id,id,revision_id);
CREATE TABLE frozen_grant (
 scope_id uuid NOT NULL REFERENCES workflow_scope(id), user_id uuid NOT NULL REFERENCES app_user(id),
 can_record boolean NOT NULL DEFAULT false, can_review boolean NOT NULL DEFAULT false, can_qc boolean NOT NULL DEFAULT false,
 qualification text NOT NULL CHECK(qualification='SYN-FROZEN-1'),
 report_auth_generation bigint NOT NULL DEFAULT nextval('report_auth_generation_seq'),
 valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until timestamptz, revoked_at timestamptz,
 PRIMARY KEY(scope_id,user_id), CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TRIGGER trg_frozen_grant_generation BEFORE UPDATE ON frozen_grant FOR EACH ROW EXECUTE FUNCTION advance_report_auth_generation();
CREATE TABLE frozen_case (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL UNIQUE,
 container_id uuid NOT NULL, material_id uuid NOT NULL UNIQUE,
 site text NOT NULL CHECK(char_length(site) BETWEEN 1 AND 255 AND btrim(site)<>''),
 version bigint NOT NULL CHECK(version>=0), owner_id uuid NOT NULL REFERENCES app_user(id), owner_active boolean NOT NULL,
 received_id uuid NOT NULL, prepared_id uuid, revision_id uuid, review_id uuid, qc_id uuid,
 qc_state text NOT NULL CHECK(qc_state IN ('NOT_ASSESSED','PASS','FAIL','IDENTITY_MISMATCH')),
 stage text GENERATED ALWAYS AS (CASE WHEN review_id IS NOT NULL THEN 'REVIEWED' WHEN revision_id IS NOT NULL THEN 'DRAFT' WHEN prepared_id IS NOT NULL THEN 'PREPARED' ELSE 'RECEIVED' END) STORED,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id,case_id),
 FOREIGN KEY(hospital_id,request_id,case_id,container_id) REFERENCES specimen_container(hospital_id,request_id,case_id,id)
);
CREATE TABLE frozen_event (
 id uuid PRIMARY KEY, frozen_id uuid NOT NULL REFERENCES frozen_case(id), case_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>=0),
 action text NOT NULL CHECK(action IN ('RECEIVE','PREPARE','CORRECT_TIME','QC_PASS','QC_FAIL','IDENTITY_MISMATCH','DRAFT','REVIEW','TRANSFER','CLAIM','COMMUNICATE','READBACK','CONFIRM','LINK_ROUTINE')),
 actor_id uuid NOT NULL REFERENCES app_user(id), target_user_id uuid REFERENCES app_user(id),
 result_id uuid, related_id uuid,
 occurred_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 zone_id text NOT NULL CHECK(char_length(zone_id) BETWEEN 1 AND 80), offset_seconds integer NOT NULL CHECK(offset_seconds BETWEEN -64800 AND 64800),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 content text NOT NULL DEFAULT '' CHECK(char_length(content)<=8000),
 dependency text CHECK(dependency ~ '^[0-9a-f]{64}$'),
 routine_signature_id uuid, routine_revision_id uuid, routine_template_code text, routine_template_version integer,
 comparison text CHECK(comparison IN ('CONSISTENT','DISCREPANCY')),
 communication_method text CHECK(communication_method='LOCAL_SIMULATION'),
 UNIQUE(frozen_id,version), UNIQUE(frozen_id,id), UNIQUE(frozen_id,id,action),
 FOREIGN KEY(frozen_id,case_id) REFERENCES frozen_case(id,case_id),
 FOREIGN KEY(frozen_id,result_id) REFERENCES frozen_event(frozen_id,id),
 FOREIGN KEY(frozen_id,related_id) REFERENCES frozen_event(frozen_id,id),
 FOREIGN KEY(case_id,routine_revision_id,routine_template_code,routine_template_version) REFERENCES report_revision(case_id,id,template_code,template_version),
 FOREIGN KEY(case_id,routine_signature_id,routine_revision_id) REFERENCES report_review_event(case_id,id,revision_id),
 FOREIGN KEY(routine_template_code,routine_template_version) REFERENCES report_template(code,version),
 CHECK(occurred_at<=recorded_at),
 CHECK((action='LINK_ROUTINE')=(routine_signature_id IS NOT NULL AND routine_revision_id IS NOT NULL AND routine_template_code IS NOT NULL AND routine_template_version IS NOT NULL AND comparison IS NOT NULL)),
 CHECK(action NOT IN ('DRAFT','READBACK','CONFIRM','LINK_ROUTINE') OR btrim(content)<>''),
 CHECK(action NOT IN ('COMMUNICATE','READBACK','CONFIRM','TRANSFER') OR target_user_id IS NOT NULL),
 CHECK(action NOT IN ('REVIEW','COMMUNICATE','READBACK','CONFIRM','LINK_ROUTINE','DRAFT') OR result_id IS NOT NULL),
 CHECK(action NOT IN ('CORRECT_TIME','READBACK','CONFIRM') OR related_id IS NOT NULL),
 CHECK(action<>'REVIEW' OR dependency IS NOT NULL),
 CHECK(action<>'DRAFT' OR result_id=id),
 CHECK((action IN ('COMMUNICATE','READBACK','CONFIRM'))=(communication_method IS NOT NULL))
);
CREATE UNIQUE INDEX uq_frozen_communication_step ON frozen_event(frozen_id,related_id,action) WHERE action IN ('READBACK','CONFIRM');
ALTER TABLE frozen_case ADD FOREIGN KEY(id,version) REFERENCES frozen_event(frozen_id,version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE frozen_case ADD FOREIGN KEY(id,received_id) REFERENCES frozen_event(frozen_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE frozen_case ADD FOREIGN KEY(id,prepared_id) REFERENCES frozen_event(frozen_id,id);
ALTER TABLE frozen_case ADD FOREIGN KEY(id,revision_id) REFERENCES frozen_event(frozen_id,id);
ALTER TABLE frozen_case ADD FOREIGN KEY(id,review_id) REFERENCES frozen_event(frozen_id,id);
ALTER TABLE frozen_case ADD FOREIGN KEY(id,qc_id) REFERENCES frozen_event(frozen_id,id);
CREATE TRIGGER trg_frozen_event_immutable BEFORE UPDATE OR DELETE ON frozen_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_frozen_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Frozen identity is immutable' USING ERRCODE='23514'; END IF;
 IF ROW(NEW.id,NEW.hospital_id,NEW.request_id,NEW.case_id,NEW.container_id,NEW.material_id,NEW.site,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.hospital_id,OLD.request_id,OLD.case_id,OLD.container_id,OLD.material_id,OLD.site,OLD.created_at) OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Frozen identity/version is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_frozen_head BEFORE UPDATE OR DELETE ON frozen_case FOR EACH ROW EXECUTE FUNCTION protect_frozen_head();

CREATE TABLE frozen_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL REFERENCES hospital(id), case_id uuid NOT NULL REFERENCES pathology_case(id),
 actor_id uuid NOT NULL REFERENCES app_user(id), action text NOT NULL CHECK(char_length(action)<=40),
 code text NOT NULL CHECK(char_length(code)<=100), trace_id uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE TRIGGER trg_frozen_rejection_immutable BEFORE UPDATE OR DELETE ON frozen_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
