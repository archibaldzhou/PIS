ALTER TABLE workflow_grant ADD COLUMN can_print boolean NOT NULL DEFAULT false CHECK(NOT can_print OR can_read);
ALTER TABLE workflow_grant ADD COLUMN can_reprint boolean NOT NULL DEFAULT false CHECK(NOT can_reprint OR can_print);
ALTER TABLE specimen_container ADD CONSTRAINT uq_container_request_identity UNIQUE(hospital_id,request_id,id);
ALTER TABLE pathology_request ADD CONSTRAINT uq_request_patient_identity UNIQUE(hospital_id,patient_id,id);
CREATE TABLE label_identity (
    container_id uuid PRIMARY KEY,
    hospital_id uuid NOT NULL,
    request_id uuid NOT NULL,
    barcode text NOT NULL UNIQUE CHECK(char_length(barcode)=34 AND barcode ~ '^S[0-9A-F]{32}[0-9A-Z. $/+%-]$'),
    FOREIGN KEY(hospital_id,request_id,container_id) REFERENCES specimen_container(hospital_id,request_id,id),
    UNIQUE(container_id,barcode),
    UNIQUE(hospital_id,request_id,container_id)
);
CREATE TABLE label_job (
    id uuid PRIMARY KEY,
    hospital_id uuid NOT NULL,
    request_id uuid NOT NULL,
    container_id uuid NOT NULL,
    barcode text NOT NULL,
    parent_job_id uuid,
    template_version text NOT NULL CHECK(template_version='SYN-CONTAINER-1'),
    state text NOT NULL CHECK(state IN ('PREVIEW_READY','FAILED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
    attempts integer NOT NULL DEFAULT 1 CHECK(attempts BETWEEN 1 AND 1000),
    request_version bigint NOT NULL CHECK(request_version>=0),
    container_version bigint NOT NULL CHECK(container_version>=0),
    patient_id uuid NOT NULL,
    patient_label text NOT NULL,
    encounter_number text NOT NULL,
    request_number text NOT NULL,
    case_number text NOT NULL,
    site text NOT NULL,
    laterality text NOT NULL,
    reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(container_id,id),
    FOREIGN KEY(container_id,barcode) REFERENCES label_identity(container_id,barcode),
    FOREIGN KEY(hospital_id,request_id,container_id) REFERENCES label_identity(hospital_id,request_id,container_id),
    FOREIGN KEY(hospital_id,patient_id,request_id) REFERENCES pathology_request(hospital_id,patient_id,id),
    FOREIGN KEY(container_id,parent_job_id) REFERENCES label_job(container_id,id),
    CHECK(parent_job_id IS NULL OR parent_job_id<>id)
);
CREATE UNIQUE INDEX uq_label_root ON label_job(container_id) WHERE parent_job_id IS NULL;
CREATE INDEX ix_label_container_created ON label_job(container_id,created_at DESC,id DESC);
CREATE TABLE label_job_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id uuid NOT NULL REFERENCES label_job(id),
    job_version bigint NOT NULL CHECK(job_version>=0),
    action text NOT NULL CHECK(action IN ('CREATE','REPRINT','RETRY','FAIL','CANCEL')),
    reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(job_id,job_version)
);
CREATE FUNCTION protect_label_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Label identity/history is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER trg_label_identity_immutable BEFORE UPDATE OR DELETE ON label_identity FOR EACH ROW EXECUTE FUNCTION protect_label_append_only();
CREATE TRIGGER trg_label_event_immutable BEFORE UPDATE OR DELETE ON label_job_event FOR EACH ROW EXECUTE FUNCTION protect_label_append_only();
CREATE FUNCTION protect_label_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'state'-'version'-'attempts') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'version'-'attempts') THEN
  RAISE EXCEPTION 'Label snapshot is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_label_snapshot BEFORE UPDATE ON label_job FOR EACH ROW EXECUTE FUNCTION protect_label_snapshot();
