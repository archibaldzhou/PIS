-- Synthetic workflow only; no policy, grants or signatures are seeded.
CREATE SEQUENCE report_auth_generation_seq;
CREATE FUNCTION advance_report_auth_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.report_auth_generation:=nextval('report_auth_generation_seq'); RETURN NEW; END; $$;
ALTER TABLE workflow_grant ADD COLUMN report_auth_generation bigint NOT NULL DEFAULT nextval('report_auth_generation_seq');
ALTER TABLE diagnosis_grant ADD COLUMN report_auth_generation bigint NOT NULL DEFAULT nextval('report_auth_generation_seq');
ALTER TABLE workflow_scope ADD COLUMN report_auth_generation bigint NOT NULL DEFAULT nextval('report_auth_generation_seq');
CREATE TRIGGER trg_workflow_report_generation BEFORE UPDATE ON workflow_grant FOR EACH ROW EXECUTE FUNCTION advance_report_auth_generation();
CREATE TRIGGER trg_diagnosis_report_generation BEFORE UPDATE ON diagnosis_grant FOR EACH ROW EXECUTE FUNCTION advance_report_auth_generation();
CREATE TRIGGER trg_scope_report_generation BEFORE UPDATE ON workflow_scope FOR EACH ROW EXECUTE FUNCTION advance_report_auth_generation();
CREATE TABLE report_review_policy (
 scope_id uuid PRIMARY KEY REFERENCES workflow_scope(id), code text NOT NULL CHECK(code='SYN-REVIEW-1'),
 separate_author_review boolean NOT NULL, separate_review_sign boolean NOT NULL
);
CREATE TRIGGER trg_report_policy_immutable BEFORE UPDATE OR DELETE ON report_review_policy FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_review_grant (
 user_id uuid NOT NULL, scope_id uuid NOT NULL, PRIMARY KEY(user_id,scope_id),
 FOREIGN KEY(user_id,scope_id) REFERENCES diagnosis_grant(user_id,scope_id),
 qualification text NOT NULL CHECK(qualification='SYN-REPORT-REVIEW-1'),
 can_review boolean NOT NULL DEFAULT false, can_simulate_sign boolean NOT NULL DEFAULT false,
 valid_from timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, valid_until timestamptz, revoked_at timestamptz,
 report_auth_generation bigint NOT NULL DEFAULT nextval('report_auth_generation_seq'),
 CHECK(valid_until IS NULL OR valid_until>valid_from)
);
CREATE TRIGGER trg_report_grant_generation BEFORE UPDATE ON report_review_grant FOR EACH ROW EXECUTE FUNCTION advance_report_auth_generation();
CREATE TABLE report_review_event (
 id uuid PRIMARY KEY, case_id uuid NOT NULL, version bigint NOT NULL CHECK(version>=0),
 revision_id uuid NOT NULL, draft_version bigint NOT NULL,
 action text NOT NULL CHECK(action IN ('APPROVE','RETURN','SIMULATE_SIGN')),
 actor_id uuid NOT NULL REFERENCES app_user(id), actor_snapshot text NOT NULL,
 dependency_snapshot text NOT NULL, dependency_evidence text NOT NULL, review_id uuid,
 reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(case_id,version,id), UNIQUE(case_id,id), UNIQUE(case_id,version),
 FOREIGN KEY(case_id,draft_version,revision_id) REFERENCES report_revision(case_id,version,id),
 FOREIGN KEY(case_id,review_id) REFERENCES report_review_event(case_id,id),
 CHECK((action='SIMULATE_SIGN')=(review_id IS NOT NULL))
);
CREATE TRIGGER trg_report_review_event_immutable BEFORE UPDATE OR DELETE ON report_review_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE TABLE report_review_head (
 case_id uuid PRIMARY KEY REFERENCES pathology_case(id), version bigint NOT NULL CHECK(version>=0), event_id uuid NOT NULL,
 FOREIGN KEY(case_id,version,event_id) REFERENCES report_review_event(case_id,version,id)
);
CREATE FUNCTION protect_report_review_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.case_id<>OLD.case_id OR NEW.version<>OLD.version+1 OR EXISTS(SELECT 1 FROM report_review_event WHERE id=OLD.event_id AND action='SIMULATE_SIGN') THEN
 RAISE EXCEPTION 'Review version or simulated signed snapshot is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_review_head BEFORE UPDATE ON report_review_head FOR EACH ROW EXECUTE FUNCTION protect_report_review_head();
CREATE FUNCTION protect_simulated_signed_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM report_review_head h JOIN report_review_event e ON e.id=h.event_id WHERE h.case_id=OLD.case_id AND e.action='SIMULATE_SIGN') THEN
 RAISE EXCEPTION 'Simulated signed draft is frozen' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_signed_draft BEFORE UPDATE OR DELETE ON report_draft FOR EACH ROW EXECUTE FUNCTION protect_simulated_signed_draft();
