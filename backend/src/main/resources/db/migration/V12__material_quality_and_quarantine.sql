ALTER TABLE technical_task ADD UNIQUE(hospital_id,request_id,id);
ALTER TABLE material_entity ADD UNIQUE(hospital_id,request_id,case_id,patient_id,id);
ALTER TABLE workflow_grant ADD COLUMN can_qc boolean NOT NULL DEFAULT false CHECK(NOT can_qc OR can_read);
CREATE TABLE quality_head (
 material_id uuid PRIMARY KEY REFERENCES material_entity(id),
 hospital_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL, patient_id uuid NOT NULL,
 cassette_id uuid, task_id uuid, block_id uuid,
 material_version bigint NOT NULL CHECK(material_version>=0), task_version bigint CHECK(task_version>=0),
 state text NOT NULL CHECK(state IN ('PASS','FAIL','PENDING','IDENTITY_MISMATCH','REVOKED','REWORK_REQUIRED','INVALIDATED')),
 version bigint NOT NULL CHECK(version>=0), assessment_id uuid, repair_task_id uuid,
 CHECK((task_id IS NULL)=(task_version IS NULL)),
 UNIQUE(hospital_id,request_id,material_id), UNIQUE(material_id,task_id),
 FOREIGN KEY(hospital_id,request_id,case_id,patient_id,material_id) REFERENCES material_entity(hospital_id,request_id,case_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,material_id) REFERENCES material_entity(hospital_id,request_id,id),
 FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(hospital_id,request_id,task_id) REFERENCES technical_task(hospital_id,request_id,id),
 FOREIGN KEY(hospital_id,request_id,repair_task_id) REFERENCES technical_task(hospital_id,request_id,id)
);
CREATE TABLE quality_assessment (
 id uuid PRIMARY KEY, material_id uuid NOT NULL REFERENCES quality_head(material_id),
 material_version bigint NOT NULL CHECK(material_version>=0), task_id uuid, task_version bigint CHECK(task_version>=0),
 standard_version text NOT NULL CHECK(standard_version='SYN-MATERIAL-QC-1'),
 outcome text NOT NULL CHECK(outcome IN ('PASS','FAIL','PENDING','IDENTITY_MISMATCH')),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id), occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK((task_id IS NULL)=(task_version IS NULL)), UNIQUE(material_id,id),
 FOREIGN KEY(material_id,task_id) REFERENCES quality_head(material_id,task_id)
);
ALTER TABLE quality_head ADD FOREIGN KEY(material_id,assessment_id) REFERENCES quality_assessment(material_id,id);
CREATE TABLE quality_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), material_id uuid NOT NULL REFERENCES quality_head(material_id),
 version bigint NOT NULL CHECK(version>=0), action text NOT NULL CHECK(action IN ('ASSESS','REVOKE','REWORK','INVALIDATE')),
 assessment_id uuid NOT NULL, related_task_id uuid REFERENCES technical_task(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 actor_id uuid NOT NULL REFERENCES app_user(id), occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(material_id,version), FOREIGN KEY(material_id,assessment_id) REFERENCES quality_assessment(material_id,id)
);
CREATE TRIGGER trg_quality_assessment_immutable BEFORE UPDATE OR DELETE ON quality_assessment FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TRIGGER trg_quality_event_immutable BEFORE UPDATE OR DELETE ON quality_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_quality_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'material_version'-'task_version'-'state'-'version'-'assessment_id'-'repair_task_id') IS DISTINCT FROM
    (to_jsonb(OLD)-'material_version'-'task_version'-'state'-'version'-'assessment_id'-'repair_task_id') THEN
  RAISE EXCEPTION 'Quality identity is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.state='IDENTITY_MISMATCH' AND NEW.state<>'IDENTITY_MISMATCH' THEN
  RAISE EXCEPTION 'Identity quarantine cannot be released' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_quality_scope BEFORE UPDATE ON quality_head FOR EACH ROW EXECUTE FUNCTION protect_quality_scope();
CREATE INDEX ix_quality_request ON quality_head(request_id,material_id);
CREATE INDEX ix_quality_task ON quality_head(task_id);
