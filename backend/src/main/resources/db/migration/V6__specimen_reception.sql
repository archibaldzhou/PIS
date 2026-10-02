ALTER TABLE workflow_grant ADD COLUMN can_receive boolean NOT NULL DEFAULT false CHECK (NOT can_receive OR can_read);
ALTER TABLE workflow_grant ADD COLUMN can_exception boolean NOT NULL DEFAULT false CHECK (NOT can_exception OR can_read);
ALTER TABLE request_workflow DROP CONSTRAINT request_workflow_state_check;
ALTER TABLE request_workflow DROP CONSTRAINT request_workflow_check;
ALTER TABLE request_workflow ADD CONSTRAINT request_workflow_state_check CHECK (state IN ('DRAFT','SUBMITTED','RECEIVED','EXCEPTION','RETURNED'));
ALTER TABLE request_workflow ADD CONSTRAINT request_workflow_submission_check CHECK (
    (state='DRAFT' AND submitted_by IS NULL AND submitted_at IS NULL)
    OR (state<>'DRAFT' AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL));
ALTER TABLE request_workflow ADD CONSTRAINT uq_request_workflow_hospital UNIQUE(hospital_id,request_id);
CREATE TABLE specimen_reception (
    request_id uuid PRIMARY KEY,
    hospital_id uuid NOT NULL,
    case_id uuid NOT NULL UNIQUE,
    received_by uuid NOT NULL REFERENCES app_user(id),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (hospital_id,request_id) REFERENCES request_workflow(hospital_id,request_id),
    FOREIGN KEY (hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id)
);
CREATE TABLE reception_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL REFERENCES request_workflow(request_id),
    request_version bigint NOT NULL CHECK (request_version>0),
    action text NOT NULL CHECK (action IN ('RECEIVE','EXCEPTION','RETURN','RESOLVE')),
    category text CHECK (category IN ('IDENTITY','QUANTITY','INFORMATION')),
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(request_id,request_version),
    CHECK ((action='EXCEPTION')=(category IS NOT NULL))
);
CREATE FUNCTION protect_reception_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Reception history is append only' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER trg_reception_event_immutable BEFORE UPDATE OR DELETE ON reception_event
FOR EACH ROW EXECUTE FUNCTION protect_reception_event();
