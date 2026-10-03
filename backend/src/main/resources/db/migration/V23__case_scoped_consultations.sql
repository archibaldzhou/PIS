ALTER TABLE report_revision ADD UNIQUE(case_id,id);
CREATE TABLE report_consultation (
 id uuid PRIMARY KEY, case_id uuid NOT NULL, hospital_id uuid NOT NULL, request_id uuid NOT NULL, scope_id uuid NOT NULL,
 revision_id uuid NOT NULL, assignment_version bigint NOT NULL CHECK(assignment_version>=0), dependencies text NOT NULL, material_basis text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('CONSULT','REREAD')), purpose text NOT NULL CHECK(char_length(purpose) BETWEEN 1 AND 2000 AND btrim(purpose)<>''),
 expires_at timestamptz NOT NULL, created_by uuid NOT NULL REFERENCES app_user(id), created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 state text NOT NULL CHECK(state IN ('OPEN','WITHDRAWN','RETURNED','ADOPTED')), version bigint NOT NULL CHECK(version>=0), summary_id uuid,
 UNIQUE(case_id,id), FOREIGN KEY(hospital_id,request_id,case_id) REFERENCES pathology_case(hospital_id,request_id,id),
 FOREIGN KEY(request_id,scope_id) REFERENCES request_workflow(request_id,scope_id), FOREIGN KEY(hospital_id,scope_id) REFERENCES workflow_scope(hospital_id,id),
 FOREIGN KEY(case_id,revision_id) REFERENCES report_revision(case_id,id), CHECK(expires_at>created_at)
);
CREATE TABLE consultation_member (
 consultation_id uuid NOT NULL REFERENCES report_consultation(id), user_id uuid NOT NULL REFERENCES app_user(id),
 qualification_snapshot text NOT NULL, state text NOT NULL CHECK(state IN ('INVITED','ACCEPTED','REJECTED','REVOKED')),
 opinion_id uuid, confirmed_summary_id uuid, PRIMARY KEY(consultation_id,user_id)
);
CREATE TABLE consultation_event (
 id uuid PRIMARY KEY, consultation_id uuid NOT NULL REFERENCES report_consultation(id), version bigint NOT NULL CHECK(version>=0),
 action text NOT NULL CHECK(action IN ('CREATE','ACCEPT','REJECT','WITHDRAW','REVOKE','RETURN','OPINION','SUMMARY','CONFIRM','ADOPT')),
 actor_id uuid NOT NULL REFERENCES app_user(id), target_id uuid REFERENCES app_user(id),
 disposition text CHECK(disposition IN ('AGREE','DISAGREE','UNKNOWN','RESOLVED','UNRESOLVED')),
 content text NOT NULL CHECK(char_length(content)<=4000), reason text NOT NULL CHECK(char_length(reason) BETWEEN 1 AND 2000 AND btrim(reason)<>''),
 basis text, summary_id uuid, adopted_revision_id uuid, case_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(consultation_id,version), UNIQUE(consultation_id,id),
 FOREIGN KEY(case_id,consultation_id) REFERENCES report_consultation(case_id,id),
 FOREIGN KEY(case_id,adopted_revision_id) REFERENCES report_revision(case_id,id),
 FOREIGN KEY(consultation_id,summary_id) REFERENCES consultation_event(consultation_id,id),
 CHECK(action NOT IN ('OPINION','SUMMARY','ADOPT') OR btrim(content)<>''),
 CHECK(action<>'OPINION' OR (disposition IS NOT NULL AND disposition IN ('AGREE','DISAGREE','UNKNOWN'))),
 CHECK(action<>'SUMMARY' OR (disposition IS NOT NULL AND disposition IN ('RESOLVED','UNRESOLVED') AND basis IS NOT NULL)),
 CHECK(action<>'ADOPT' OR (adopted_revision_id IS NOT NULL AND summary_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_consult_adopt ON consultation_event(consultation_id) WHERE action='ADOPT';
ALTER TABLE report_consultation ADD FOREIGN KEY(id,version) REFERENCES consultation_event(consultation_id,version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE report_consultation ADD FOREIGN KEY(id,summary_id) REFERENCES consultation_event(consultation_id,id);
ALTER TABLE consultation_member ADD FOREIGN KEY(consultation_id,opinion_id) REFERENCES consultation_event(consultation_id,id);
ALTER TABLE consultation_member ADD FOREIGN KEY(consultation_id,confirmed_summary_id) REFERENCES consultation_event(consultation_id,id);
CREATE INDEX ix_consult_case ON report_consultation(case_id,created_at,id);
CREATE INDEX ix_consult_member_user ON consultation_member(user_id,consultation_id);
CREATE TRIGGER trg_consult_event_immutable BEFORE UPDATE OR DELETE ON consultation_event FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION protect_consultation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Consultation history is immutable' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','version','summary_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','summary_id']) OR NEW.version<>OLD.version+1 OR OLD.state<>'OPEN' THEN RAISE EXCEPTION 'Consultation identity or terminal state is immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_consultation BEFORE UPDATE OR DELETE ON report_consultation FOR EACH ROW EXECUTE FUNCTION protect_consultation();
CREATE FUNCTION protect_consult_member() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Participant history is immutable' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ARRAY['state','opinion_id','confirmed_summary_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','opinion_id','confirmed_summary_id']) OR OLD.state IN ('REJECTED','REVOKED') OR NOT ((OLD.state='INVITED' AND NEW.state IN ('ACCEPTED','REJECTED','REVOKED')) OR (OLD.state='ACCEPTED' AND NEW.state IN ('ACCEPTED','REJECTED','REVOKED'))) THEN RAISE EXCEPTION 'Invalid participant transition' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER trg_consult_member BEFORE UPDATE OR DELETE ON consultation_member FOR EACH ROW EXECUTE FUNCTION protect_consult_member();

CREATE TABLE consultation_rejection (
 id uuid PRIMARY KEY, hospital_id uuid NOT NULL, request_id uuid NOT NULL, actor_id uuid NOT NULL REFERENCES app_user(id),
 action text NOT NULL, code text NOT NULL, trace_id uuid NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY(hospital_id,request_id) REFERENCES pathology_request(hospital_id,id)
);
CREATE TRIGGER trg_consult_rejection_immutable BEFORE UPDATE OR DELETE ON consultation_rejection FOR EACH ROW EXECUTE FUNCTION protect_gross_append_only();
CREATE FUNCTION validate_consult_member_links() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.opinion_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM consultation_event WHERE id=NEW.opinion_id AND consultation_id=NEW.consultation_id AND action='OPINION' AND actor_id=NEW.user_id) THEN RAISE EXCEPTION 'Opinion must belong to participant' USING ERRCODE='23514'; END IF;
 IF NEW.confirmed_summary_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM consultation_event WHERE id=NEW.confirmed_summary_id AND consultation_id=NEW.consultation_id AND action='SUMMARY' AND disposition='RESOLVED') THEN RAISE EXCEPTION 'Confirmation requires explicit summary' USING ERRCODE='23514'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER trg_consult_member_links BEFORE INSERT OR UPDATE ON consultation_member FOR EACH ROW EXECUTE FUNCTION validate_consult_member_links();
