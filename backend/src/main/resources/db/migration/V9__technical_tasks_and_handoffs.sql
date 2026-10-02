ALTER TABLE workflow_grant ADD COLUMN can_process boolean NOT NULL DEFAULT false CHECK(NOT can_process OR can_read);
ALTER TABLE workflow_grant ADD COLUMN can_handoff boolean NOT NULL DEFAULT false CHECK(NOT can_handoff OR can_process);
CREATE TABLE technical_task (
 id uuid PRIMARY KEY,
 hospital_id uuid NOT NULL,
 request_id uuid NOT NULL,
 case_id uuid NOT NULL,
 record_id uuid NOT NULL,
 cassette_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('PROCESSING','EMBEDDING','SECTIONING')),
 state text NOT NULL CHECK(state IN ('QUEUED','ACTIVE','HANDOFF_PENDING','SIMULATED_DONE','ABORTED')),
 owner_id uuid REFERENCES app_user(id),
 predecessor_id uuid,
 rework_of uuid UNIQUE,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_by uuid NOT NULL REFERENCES app_user(id),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(state NOT IN ('ACTIVE','HANDOFF_PENDING','SIMULATED_DONE') OR owner_id IS NOT NULL),
 CHECK(predecessor_id IS NULL OR predecessor_id<>id),
 CHECK(rework_of IS NULL OR rework_of<>id),
 UNIQUE(hospital_id,request_id,case_id,record_id,cassette_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id) REFERENCES gross_cassette(hospital_id,request_id,case_id,record_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id,predecessor_id) REFERENCES technical_task(hospital_id,request_id,case_id,record_id,cassette_id,id),
 FOREIGN KEY(hospital_id,request_id,case_id,record_id,cassette_id,rework_of) REFERENCES technical_task(hospital_id,request_id,case_id,record_id,cassette_id,id)
);
CREATE TABLE technical_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 task_id uuid NOT NULL REFERENCES technical_task(id),
 task_version bigint NOT NULL CHECK(task_version>=0),
 action text NOT NULL CHECK(action IN ('CREATE','CLAIM','OFFER','ACCEPT','WITHDRAW','FINISH_SIMULATION','ABORT','REWORK','REWORK_CREATED')),
 actor_id uuid NOT NULL REFERENCES app_user(id),
 previous_owner_id uuid REFERENCES app_user(id),
 next_owner_id uuid REFERENCES app_user(id),
 related_task_id uuid REFERENCES technical_task(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(task_id,task_version)
);
CREATE FUNCTION protect_technical_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'state'-'owner_id'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'owner_id'-'version') THEN
  RAISE EXCEPTION 'Technical task identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_technical_identity BEFORE UPDATE ON technical_task FOR EACH ROW EXECUTE FUNCTION protect_technical_identity();
CREATE TRIGGER trg_technical_event_immutable BEFORE UPDATE OR DELETE ON technical_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE INDEX ix_technical_request ON technical_task(request_id,created_at,id);
CREATE INDEX ix_technical_predecessor ON technical_task(predecessor_id);
