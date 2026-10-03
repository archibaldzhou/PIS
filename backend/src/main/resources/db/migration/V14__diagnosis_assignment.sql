-- Synthetic qualification only. No users, grants or automatic assignments are seeded.
CREATE TABLE diagnosis_grant (
 user_id uuid NOT NULL, scope_id uuid NOT NULL,
 can_assign boolean NOT NULL DEFAULT false, can_diagnose boolean NOT NULL DEFAULT false,
 qualification text NOT NULL CHECK(qualification='SYN-DIAG-ASSIGNMENT-1'),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until timestamptz, revoked_at timestamptz,
 PRIMARY KEY(user_id,scope_id), FOREIGN KEY(user_id,scope_id) REFERENCES workflow_grant(user_id,scope_id),
 CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TABLE diagnosis_assignment (
 case_id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL,
 state text NOT NULL CHECK(state IN ('ASSIGNED','ACTIVE')), owner_id uuid NOT NULL REFERENCES app_user(id),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id)
);
CREATE TABLE diagnosis_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), case_id uuid NOT NULL REFERENCES diagnosis_assignment(case_id),
 version bigint NOT NULL CHECK(version>=0), action text NOT NULL CHECK(action IN ('ASSIGN','CLAIM','TRANSFER')),
 actor_id uuid NOT NULL REFERENCES app_user(id), previous_owner_id uuid REFERENCES app_user(id), next_owner_id uuid NOT NULL REFERENCES app_user(id),
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(case_id,version)
);
CREATE FUNCTION protect_diagnosis_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-'state'-'owner_id'-'version') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'owner_id'-'version') OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'Diagnosis identity/version is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_diagnosis_identity BEFORE UPDATE ON diagnosis_assignment FOR EACH ROW EXECUTE FUNCTION protect_diagnosis_assignment();
CREATE TRIGGER trg_diagnosis_event_immutable BEFORE UPDATE OR DELETE ON diagnosis_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE INDEX ix_diagnosis_request ON diagnosis_assignment(request_id,case_id);
